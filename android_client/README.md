# Android Registration Client — Liveness Gate Integration

Implements the *Face Liveness + PAD orchestration* spec on top of this repo's
shared engine (`src/main/java/io/mosip/liveness/**`). One orchestrator drives
liveness for all three workflows (resident capture, operator auth, supervisor
auth) so behaviour is identical online/offline and across roles.

## What is engine-tested vs Android-only

| Tree | Runs where | Notes |
|---|---|---|
| `src/main/java/io/mosip/liveness/android/**` | JVM (this repo) | Full state machine, policy floor, evidence signing — covered by `AndroidLivenessOrchestratorTest` (13 tests) and the shared engine suite (349 green). |
| `pigeon/liveness.dart` | Regenerated code | Host/Flutter API definitions (spec §7). |
| `lib/liveness/**` | Flutter | `LivenessViewModel` + `LivenessView`: renders state, prompts (i18n keys), retry; capture button enabled only on PASSED (§14). |
| `android/app/src/main/java/io/mosip/registration/liveness/**` | Android embedding | `CameraXFaceSource`, `AndroidKeystoreEvidenceSigner`, `LivenessPigeonBridge` glue. Touches Android APIs, so it is wired by hand (see below), not compiled by this repo's Maven build. |

## Engine-side components (already in `src/main/java`)

| Component | Responsibility (spec §2) |
|---|---|
| `AndroidLivenessOrchestrator` | State machine (§4), attempt/retry counting, session nonces, gate validity, audit |
| `AndroidLivenessPolicyProvider` | Per-role resolution over the shared `WorkflowPolicyDefaults` table + hard security floor (§3) |
| `FaceFrameSource` + `MockFaceFrameSource` | Vendor-neutral frame SPI; mock for tests/dev (§11) |
| `LivenessEvidence(Signer)` | Signed evidence record, SHA-256withRSA (analyse.md §5.4, §8 `pass()`) |
| `ModelStore` / `InMemoryModelStore` / `SignedManifestModelStore` | Model activation bound to a vendor-signed manifest (spec §13: signature → sha256 → atomic swap → rollback); tamper-tested by `SignedManifestModelStoreTest` |
| `LockoutStore` | `lockoutUntil` persistence for `LOCKOUT_TEMPORARY` (§8, §12) |

Design invariants enforced (§1): fail closed (R1); frames never cross the
Pigeon channel (R2); orchestrator selects challenges (R3); no network in any
decision (R4); UI gets generic i18n keys only (R5).

## Flutter-side integration (§14)

1. Generate the Pigeon code:
   `dart run pigeon --input pigeon/liveness.dart`
2. Host a `LivenessViewModel` (provider/riverpod) and render `LivenessView`
   inside the resident face-capture screen and the operator/supervisor
   biometric login dialogs (role param).
3. Add the §9 message keys to `intl_*.arb` files (defaults in
   `LivenessView.defaultMessages`).
4. Enable the capture button only when `viewModel.captureEnabled` is true and
   `ensureGateValidForCapture()` returns true (gate validity window, §8).

## Android embedding steps

1. Add the engine jar (`pad-liveness-backend` or the extracted
   `io.mosip.liveness` classes) to `clientmanager`'s dependencies
   (see docs/client-integration-guide.md §4.1).
2. Copy `android/app/src/main/java/io/mosip/registration/liveness/**` into the
   client's Android module; declare CameraX deps and ProGuard keep rules for
   `io.mosip.liveness.**` (§14 build).
3. In the Flutter `Activity`, construct `LivenessPigeonBridge` with the
   generated Pigeon registrar, bind the Activity as `LifecycleOwner` for
   `CameraXFaceSource`, and apply `FLAG_SECURE` on liveness screens
   (`LivenessPigeonBridge.applyFlagSecure`).
4. Wire `AndroidModelStore` (this directory): it reads the vendor-signed
   manifest + model bundle from disk and delegates verification to the
   engine's `SignedManifestModelStore` (§13: signature → sha256 → atomic
   swap → rollback — fail-closed, tamper-tested in the engine suite). Back
   `LockoutStore` with Room so `lockoutUntil` survives process death (§12).

## Version constraints (verified against MOSIP docs + the RC repo, 2026-10-06)

Sources: MOSIP Docs 1.2.0 *Technology Stack* page (via the repo's research
note) and the live `mosip/android-registration-client` repository (`main`,
v1.1.1).

| Pin | Value | Source / notes |
|---|---|---|
| Flutter | **3.10.4** | Docs 1.2.0 Technology Stack; RC repo README |
| Dart | **3.0.3** (Flutter 3.10.4 bundle) | same Technology Stack page |
| Android compile SDK | Docs say **31**; RC repo `main` uses **`compileSdkVersion 34`** | **docs-vs-repo drift** — the repo has moved on (AGP 8.x comment in `pubspec` history); pin to the repo at integration, keep `targetSdk` = `flutter.targetSdkVersion` as the repo does |
| min SDK | **28** (RC repo) | the liveness module needs no raise |
| MOSIP platform | **1.2.0.1 (Java 11 backend)** compatible; 1.2.1.0 (Java 21) listed not compatible | this is a *backend platform* figure, not the app's language level; an upstream `j11-to-j21` migration page exists |
| RC app language level | **Java 21** (`sourceCompatibility`/`targetCompatibility VERSION_21`, core-library desugaring on) | RC repo `android/app/build.gradle` (`main`) |
| Engine (this repo) | **Java 17** (`pom.xml` `<java.version>17</java.version>`; records + switch expressions) | class file 61 |

### The Java-11-vs-17 engine-jar question — resolved

- **Android embedding:** the RC app compiles at Java 21 with desugaring, so
  the engine jar built at `--release 17` (class file 61) links and dexes
  cleanly — `javac 21` reads it, D8/R8 translates it. Verified against the
  repo's `compileOptions`, not assumed.
- **A fork still pinned to Java 11 compileOptions cannot link the jar**
  (records require ≥ 16): raise that build to ≥ 17; there is no source-level
  fallback that keeps records off the table.
- **Backend platform:** the Java 11 figure belongs to MOSIP platform
  1.2.0.1. This repo's Spring Boot **service** (3.3.3) requires JDK ≥ 17, so
  it cannot run *on* a Java-11 platform runtime — deploy it on 17+ or follow
  the platform's own j11→j21 path. The Android embed takes only the
  `io.mosip.liveness` classes, never Spring.

### CameraX choice

- **Pin `androidx.camera:*:1.3.x` (≥ 1.3.0).** `CameraXFaceSource` uses
  `ResolutionSelector` / `ResolutionStrategy` / `AspectRatioStrategy`, which
  exist only from CameraX **1.3.0** (the legacy `setTargetResolution` was
  deprecated there — verified in the androidx camera release notes).
- Compatible with the RC repo's `compileSdkVersion 34`.
- **Fallback for an SDK-31 toolchain** (if integrating against the docs-31
  stack instead): use CameraX **1.2.x** and rewrite `open()` to the legacy
  `setTargetResolution(Size)` path — the `resolutionselector` classes do not
  exist before 1.3.
- The RC repo declares no CameraX dependency today (device capture goes
  through the SBI app), so this is a **new dependency** to add alongside the
  ProGuard keep rules in the embedding steps above.

### Pigeon choice

- **Pin `pigeon: ^10.0.1`** — exactly what the RC repo's own
  `pubspec.yaml` declares (verified on `main`), so generated code matches the
  repo's `pigeon.sh` workflow instead of introducing a second generator
  version.
- Regenerate with `dart run pigeon --input pigeon/liveness.dart` (the input
  file uses Pigeon-10-compatible `@HostApi`/`@FlutterApi` annotations).
- **Flagged repo drift:** RC `pubspec.yaml` still carries
  `environment: sdk: ">=2.19.6 <3.0.0"` while the docs state Dart 3.0.3 —
  reconcile that constraint in the RC repo when integrating.

## Auto-logout interplay with liveness sessions

Docs 1.2.0 lists **"Auto logout after inactivity"** and calls out the
interaction explicitly: *avoid logout mid-challenge or pause the timer*. The
race: a liveness gate is an active, seconds-to-minutes operation during which
the user may not tap the screen.

**How long a gate can hold the screen (bounded by code, not hope):**

- One attempt is capped by the engine at `maxSessionDurationMs`
  (**default 30 s**) and fails closed with `SESSION_TIMEOUT`.
- Attempts are capped by `CEILING_MAX_RETRIES` (**5**) — so a worst-case gate
  is ≈ **5 × 30 s = 150 s**, and a typical passive pass is **under 10 s**.
- A flow that stacks gates (resident capture → operator auth → supervisor
  auth) multiplies that; size the timer against the stack, not one gate.

**Decision (host-side pause; the module cannot pause the timer itself):**
the inactivity watchdog lives in the RC app, and the liveness module only
emits events. The integration contract is:

1. On `startSession` success → suppress/refresh the inactivity countdown.
2. Refresh again on every `onState` event (prompt/progress = live user).
3. On `onFinal` (`PASSED` | `TERMINAL_FAILURE` | `ABORTED`) → resume the
   normal countdown.
4. As a floor, the RC inactivity timeout should exceed the worst-case gate
   (150 s) even without pause support.

**Fail-safe if logout fires anyway** (old RC build, timer raced): the
`LivenessViewModel` is disposed with the screen → `cancelSession()` →
session ends `ABORTED` → the gate fails closed; the next screen demands
re-login, and stale evidence never satisfies `ensureGateValidForCapture()`
(expiry/abort are both fail-closed — covered by the engine test suite). So
the race costs a retry, never a bypass.

Until the RC host wires steps 1–3, this remains *integration-pending*
(tracker G4, in progress).

## Acceptance coverage (§15)

Verified by tests in this repo: passive pass without user action; low score →
engine-selected challenge (user never chooses); PAD blocks with the generic
message; device errors never consume the retry budget; missing model fails
closed; retry limit + lockout persisted; gate validity/expiry enforced before
capture; unsigned evidence withheld (fail closed); policy floor cannot be
weakened; config cannot disable liveness without the build flag.

On-device-only items (need hardware/vendor SBI): APCER/BPCER report on a real
attack set; two physical frame-source adapters; performance targets on the
low-end device.
