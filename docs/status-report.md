# MOSIP Face Liveness & PAD Engine — Status Report

**Date:** 2026-08-26 · **Branch:** `development` · **Artifact:** `io.mosip.liveness:face-liveness-engine:0.1.0-SNAPSHOT`
**Last updated:** 2026-08-26 — Device adapter layer completed

---

## 1. Inputs Reviewed

| Source | What it is |
|---|---|
| `04_Face_Liveness_Detection_and_Presentation.pdf` | MOSIP Decode problem statement: hybrid passive→active liveness + PAD for resident capture / operator auth / supervisor auth; offline-capable; Mandatory / Bonus / Good-to-have task lists; deliverables = technical design, UI/UX design, implementation |
| Technical Design v1 (`face_liveness_pad_technical_design.md`) | Two-platform design (Desktop JavaFX + Android Flutter): ONNX Runtime + Silent-Face-Anti-Spoofing stack, engine SPI, config keys, error codes, file-by-file plans, §19 open questions Q1–Q10 |
| This repository | Phase-1 core engine library (Desktop Java) implementing the passive→active pipeline behind a pluggable backend SPI |

---

## 2. Existing Code — What Is Implemented

49 Java source files, 12 test classes. **All 86 tests pass.**

### 2.1 Architecture layers (`docs/design.md`)

```
Host Application ──▶ FaceLivenessEngine (session mgr · decision logic · challenge selector
                                        audit · metrics · config resolver)
                          │ LivenessBackend interface (pluggable)
                          ▼
             MockLivenessBackend / TfLiteMiniFasNetBackend / MediaPipeFaceMeshBackend / (vendor swap)

Device Layer:
  DeviceAdapter interface ──▶ SbiStreamDeviceAdapter (L1 STREAM endpoint)
                             └──▶ BurstRCaptureDeviceAdapter (L0 discrete rCapture)
  MockL0L1Device ──▶ in-process simulated device + embedded SBI HTTP server
  MjpegDecoder / PixelFormats / SyntheticScene (supporting utilities)
```

### 2.2 Implemented capabilities vs problem statement

| Requirement | Status |
|---|---|
| Frame-streaming interface (`Frame`, NV21/YUV420/RGB formats) | ✅ `core.Frame` + `LivenessPipeline` |
| Passive liveness w/ configurable threshold, sliding-window **median** voting | ✅ `engine.LivenessDecisionLogic` |
| Auto escalation passive → active when below threshold | ✅ `FaceLivenessEngine` state machine |
| Dynamic, unpredictable challenge selection (shuffled bag, SecureRandom) | ✅ `challenge.ChallengeSelector` |
| Facial action validation (blink EAR, smile, head yaw ±12°, gaze tolerance) | ✅ `challenge.ChallengeEvaluators` |
| PAD terminal-blocking with attack-type taxonomy (print/screen/video) | ✅ `backend.*`, `core.PadVerdict` |
| Config: thresholds, min challenges, timeout, retries, per-workflow overrides, repeated-failure policy | ✅ `config.LivenessConfig` + `EffectivePolicy` |
| Error handling codes (face not detected, multiple faces, poor quality, engine errors) | ✅ `core.LivenessErrorCode`, retryable vs terminal distinction |
| Audit & diagnostic logging (no PII) + anonymized metrics | ✅ `audit.StructuredAuditLogger`, `MetricsCollector` |
| ISO/IEC 30107 alignment (APCER/BPCER/ACER + evaluation harness) | ✅ `eval.PadMetrics`, `eval.AttackScenarioHarness` |
| Retry policy + LOCK_OUT / FALLBACK / ESCALATE_TO_OPERATOR | ✅ `RepeatedFailureAction` |
| Device adapter layer (L0/L1 frame sources, mock device, MJPEG decode) | ✅ `device.*` — `SbiStreamDeviceAdapter`, `BurstRCaptureDeviceAdapter`, `MockL0L1Device` |

### 2.3 Backends

| Backend | Role | Notes |
|---|---|---|
| `MockLivenessBackend` | CI/regression | Fully scriptable subject signals; no native deps |
| `TfLiteMiniFasNetBackend` | Real PAD | Reflection-loaded TFLite + OpenCV Haar detection; ⚠️ see §4 blocker |
| `MediaPipeFaceMeshBackend` | High-fidelity landmarks | 468-pt mesh, iris gaze, solvePnP head pose w/ roll |

### 2.4 Device adapter layer (completed 2026-08-26)

| Component | Role |
|---|---|
| `DeviceAdapter` interface | Vendor-agnostic frame source lifecycle: `open() → startCapture() → stopCapture() → close()` |
| `SbiStreamDeviceAdapter` | L1 devices: MJPEG STREAM consumer over HTTP with reconnect backoff + throttle |
| `BurstRCaptureDeviceAdapter` | L0 devices: polls `CaptureSource` (rCapture / vendor SDK grab) at target FPS |
| `MockL0L1Device` | In-process simulated device: renders `SyntheticScene`, supports failure injection, exposes built-in SBI HTTP server |
| `MjpegDecoder` | Incremental JPEG extractor from multipart/x-mixed-replace byte stream |
| `PixelFormats` | Buffer conversion: `BufferedImage` → RGB_888 / RGB_GRAY / NV21 payloads |
| `SyntheticScene` | Scriptable deterministic scene with face-like subject for testing |
| `DeviceCapabilities` | Static capability description (L0 vs L1, formats, max FPS) |

All 31 device adapter tests pass. `SbiStreamDeviceAdapter` integration test validated end-to-end against `MockL0L1Device`'s embedded HTTP server.

### 2.5 Current evaluation numbers (mock backend)

Latest run of `AttackScenarioHarnessTest` (240 presentations, seeded):

```
APCER=0.0167  BPCER=0.0000  ACER=0.0083  escalationRate=0.0917
labels: BONA_FIDE=60, PRINTED_PHOTO=60, SCREEN_REPLAY=60, VIDEO_REPLAY=60
wall time: 90 ms total (~0.37 ms/presentation)
```

⚠️ These are **pipeline-logic** metrics only — attack-miss (3%) and bona-fide FP (1%) rates are *injected* by the mock, not measured. They validate orchestration (PAD terminality, escalation, retries), not any real model's accuracy.

---

## 3. The Plan (from Technical Design v1)

Phasing (§18 of design doc):

1. **Foundations** — DTOs/enums, engine SPI + default implementation, config keys, error codes ← *largely done in this repo as a standalone library*
2–4. **Desktop** — Streamer frame hook → resident capture gate → active/PAD UI → operator/supervisor auth gate (`SessionContext.validateFace`)
5–7. **Android** — STREAM consumer (or burst-rCapture fallback) → Dart challenge overlay via pigeon bridge → operator/supervisor auth parity
8. Config/telemetry polish + Good-to-have items
9. Docs + ISO 30107 certification-readiness notes

Key ground rules honored: client-side engine (no MDS changes), zero SBI protocol changes, no mandatory cloud call, one conceptual design across both platforms.

---

## 4. Real-Model Evaluation — Feasibility Findings (2026-08-26)

Goal: run `AttackScenarioHarness` against the **real** PAD model and report true APCER/BPCER.

### Findings

1. **Model available:** `silentface.tflite` on HuggingFace (`litert-community/Silent-Face-Anti-Spoofing-LiteRT`) and, better for Desktop, `minifasnet_v2.onnx` (`garciafido/minifasnet-v2-anti-spoofing-onnx`) — Apache-2.0, bit-equivalent to upstream `2.7_80x80_MiniFASNetV2.pth`, documented preprocessing (80×80 BGR crop w/ 2.7× margin, ÷255, 3-class softmax `[live, print, replay]`).
2. **Blocker — TFLite cannot run on desktop JVM:** the only official runtime (`com.google.ai.edge.litert:litert`) is an Android-only AAR with ARM-only natives. There is no desktop artifact carrying `org.tensorflow.lite.Interpreter`. `TfLiteMiniFasNetBackend.initialize()` therefore throws on this machine.
3. **Unblock path:** ONNX Runtime (`com.microsoft.onnxruntime:onnxruntime`) is first-class on desktop JVM **and is the design doc's primary recommended stack (§6.2)** — an `OnnxMiniFasNetBackend` behind the same `LivenessBackend` SPI keeps everything else unchanged.
4. **Harness limitation:** `AttackScenarioHarness.runOne()` hard-wires `MockLivenessBackend`; it needs a refactor to accept an injected backend.
5. **Corpus gap:** no labeled corpus of *physical* presentations exists anywhere on disk. True ISO-grade APCER/BPCER requires recorded device captures (real prints, real screen replays). Until then, only **proxy** estimates (simulated print/replay degradation of live photos) are possible and must be labeled as such.
6. Nothing `.tflite`/`.onnx`, no cascade XMLs, and no capture data found locally.

### Decision recorded
Proceed with ONNX Runtime pivot (user delegated); TFLite backend left untouched; results clearly marked proxy until real captures exist.

---

## 5. Follow-Up Options

| # | Option | Outcome |
|---|---|---|
| 1 | **Real-model eval (in progress direction):** download + SHA-verify `minifasnet_v2.onnx`, add provided-scope ORT dependency, implement `OnnxMiniFasNetBackend`, refactor harness to accept injected backends, build proxy corpus, run end-to-end | Proxy APCER/BPCER measured through the full pipeline today; runner reusable for real captures later |
| 2 | ~~**Device adapter layer:**~~ SBI STREAM consumer + burst-rCapture fallback + mock L0/L1 device (Bonus task #1) | ✅ **Done.** Closes the frame-source gap between this library and actual biometric devices |
| 3 | **UI/UX deliverable:** screen-by-screen design (Desktop + Android) per problem-statement §10 | Second required deliverable, not yet written |
| 4 | **Model integrity checks:** checksum/signature verification on model load in backends | Security hardening flagged in design §16 |
| 5 | **Resolve design-doc open questions Q1–Q10** (recovery path, packet persistence, STREAM capability probe, factory fork, etc.) | Locks implementation defaults before host-app integration |
| 6 | **Host-app integration phases 2–7** once 1–5 land | The actual registration-client workstreams |

---

## 6. Files Changed This Session

| File | Change |
|---|---|
| `device/BurstRCaptureDeviceAdapter.java` | **New.** L0 burst-capture adapter polling `CaptureSource` at target FPS |
| `device/SbiStreamDeviceAdapter.java` | Fix: missing `IOException` import; `lastEmitNanos` promoted to field |
| `device/DeviceAdapterTest.java` | **New.** 31 tests covering all device adapter components |

---

## 7. Honest Caveats

- No open-source model can claim "ISO/IEC 30107 certified" — certification applies to a deployed system tested by an accredited lab. This engine is ISO-taxonomy-aligned and certification-*ready* by design (structured per-attempt telemetry + swappable backend).
- Mock-backend harness numbers measure orchestration correctness, not model accuracy.
- Proxy-corpus numbers will overstate real-world performance (especially video-replay, which print/replay simulations approximate poorly).
