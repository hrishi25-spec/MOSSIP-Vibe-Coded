# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased] - 2026-10-04

### Added
- Config audit entries are now **immutable and tamper-evident**. Each
  `CONFIG_CHANGED` row stores `prev_hash` (the previous entry's hash, `GENESIS`
  for the first) and `entry_hash = SHA-256(prev_hash ‖ canonical(entry))`
  (`V9__audit_chain_immutability.sql`), so editing a row invalidates its own
  hash and deleting one leaves every successor pointing at a hash that no
  longer follows. Immutability is enforced by the **database**, not by Java:
  `BEFORE UPDATE` / `BEFORE DELETE` triggers that raise, because a Java-side
  rule is bypassed by anything holding a connection. Those triggers also fire
  for the `ON DELETE CASCADE` from `liveness_sessions`, so deleting a session
  that has audit rows now fails rather than silently erasing the evidence —
  nothing in the application deletes sessions, so that is now enforced instead
  of assumed.
  Three subtleties the implementation had to get right, each caught by a test
  rather than by reading:
  - `details` round-trips through a converter that rebuilds it as a plain
    `HashMap`, so hash order is **not** stable across a database round-trip.
    Hashing what came out of the map would fail every verification for reasons
    unrelated to tampering. The payload is canonicalised first — keys sorted
    recursively, every component **length-prefixed** so a value containing the
    separator cannot be re-split into different fields.
  - `id` is assigned by Hibernate at INSERT, so it is null at hash time and the
    canonical form would change the moment the row lands; it cannot be hashed
    afterwards either, because the new trigger rejects the UPDATE. `id` is
    therefore deliberately excluded — chain position plus `prev_hash` already
    binds an entry uniquely.
  - The chain is walked in `created_at` order, so two edits inside one
    millisecond would make a perfectly intact trail fail. Timestamps are
    nudged forward by 1 ms rather than left ambiguous: a false alarm on a
    security control is its own kind of bug.
  Read-head and write-head happen under one lock, so two concurrent admin
  requests cannot both chain onto the same predecessor and fork the trail.
  `GET /api/v1/config/audit/verify` walks the whole chain (never a page — a
  break past the page boundary would read as "intact") and reports the first
  break, distinguishing an **edited** row from a **deleted** one, since the
  remedy differs. Verified live: five consecutive PUTs chain and report
  `intact: true` with a head hash.
  **Ceiling, stated plainly:** this detects corruption and edits by anyone who
  does not rebuild the chain — including the admin-key holder working through
  the API, which is the realistic threat here. It does **not** stop an attacker
  with database write access, who can read the stored hashes and recompute the
  chain. Closing that means keying the hash with a secret the database does not
  hold (HMAC, secret from the environment) or anchoring the chain head where a
  database writer cannot reach. Neither is done here.
- `audit_logs` gained a `workflow_type` column (`V8__audit_logs_workflow_type.sql`),
  so `GET /api/v1/config/audit` can be filtered **in the database** with
  `?workflowType=`. The audit table grows one row per frame decision, so
  narrowing in the browser meant paging through the entire pipeline history to
  answer a policy question; the feed now has a purpose-built finder
  (`findByEventTypeAndWorkflowTypeAndSessionIsNullOrderByCreatedAtDesc`) served by
  a new `idx_audit_logs_config_feed(event_type, workflow_type, created_at DESC)`.
  The migration backfills existing rows twice — from `liveness_sessions` for
  pipeline events, and from the `details` JSON for session-less `CONFIG_CHANGED`
  rows, which is the only place the old history recorded the workflow — so
  nothing already logged becomes unfilterable. The column is nullable: an entry
  that cannot be resolved stays NULL rather than being guessed at.
  The value stays in `details.workflowType` as well; the column is for indexing,
  not a replacement for the audit payload. `DecisionEngineService` populates it
  too, so pipeline rows are not left NULL and any future "everything for
  OPERATOR" query covers the whole trail. `workflowType` is now a first-class
  field on `AuditLogEntry`. An unknown workflow is a **400**, so a typo cannot
  silently return the unfiltered feed and read as "this workflow has no
  history". The console's Policy change history panel gained a workflow dropdown
  that pushes the filter to the server and reloads on change.
- Fixed: the console's Operational metrics panel rendered the new
  `pipelineTimings` object as `[object Object]`, because the generic key/value
  renderer stringified it. Nested objects now render one indented line per
  phase (`decode: 21.7 ms mean · 159.7 ms max · n=12 (6.3%)`), with
  `white-space: pre-wrap` on the value cell so the lines survive.
- The rate limiter's clock is now injectable (`java.time.Clock`, via an
  `ObjectProvider` so a `@WebMvcTest` slice without `AppConfig` still gets real
  time), with a `rateLimitClock` bean in `AppConfig`. Window rollover can
  finally be tested by moving time instead of sleeping out a 60-second window:
  three new `RateLimitFilterTest` cases drive a mutable clock to prove the
  session-create window resets, the frame window resets on its own clock, and
  the one-arg constructor still uses real time.
- The integration suite no longer shares a rate-limit budget with itself.
  Every test in a class runs in one process from one address, so the production
  30 sessions/min was being consumed by the suite and failing tests with 429s
  depending on order. `@TestPropertySource` now raises both budgets
  (test-only), and `rateLimitBudget_isPublishedOnLimitedPosts` asserts the
  *configured* limit instead of the literal `"30"`/`"60"` — `RateLimitFilterTest`
  is where the real defaults stay pinned, which is the correct layering.
  **Parallel execution was attempted and reverted.** The blocker is not the
  limiter: nearly every integration test first PUTs the shared RESIDENT policy
  to stage its scenario, so concurrent methods overwrite each other's setup.
  `@Execution(CONCURRENT)` failed 3/3 runs —
  `configChange_isTraceableInTheConfigAuditView` saw `CREATED` where it expected
  `UPDATED` because another test had re-created the policy meanwhile — and the
  class took ~75 s sequentially and ~75 s in parallel, so there was no wall-clock
  win to trade the risk for. Real parallelism needs per-test policy isolation.
- A PostgreSQL schema gate (`PostgresSchemaTest` + a `schema` CI job) so
  entity/migration drift fails the build instead of hiding. Every other test
  runs on H2 with `ddl-auto: create-drop`, which means Hibernate builds the
  schema *from the entities* and Flyway never runs — so a column an entity
  declares but no migration creates, a column too narrow for its values, or an
  enum stored as a number would stay green in CI and break in production, which
  is PostgreSQL, the one database never exercised. The test boots the real
  application against a real PostgreSQL with the **production** datasource
  configuration (the same `POSTGRES_*` variables `application.yml` already
  reads, not test-only properties), lets Flyway build the schema, and has
  Hibernate `validate` compare it against the entities, then round-trips a row
  to prove the mapping — including `OffsetDateTime` against `TIMESTAMPTZ`, the
  classic H2-passes/Postgres-fails difference.
  Two guards keep it honest: it asserts the JDBC product really is PostgreSQL
  and that `ddl-auto` really is `validate`, so it cannot silently pass on H2
  (verified: forcing it onto the dev profile fails all three with *"expected
  PostgreSQL but connected to H2"*, *"expected `<validate>` but was
  `<create-drop>`"* and a missing `flyway_schema_history`). Gated behind
  `MOSIP_PG_TEST=true` so a plain `./mvnw test` still needs no services, and
  excluded from the unit job's surefire filter so it runs only where a database
  exists. Uses a CI **service container** rather than Testcontainers — the
  PostgreSQL JDBC driver and `flyway-database-postgresql` are already
  dependencies, so this adds no new test libraries.
  *Not verified locally:* the sandbox's Docker cannot pull `postgres:16`
  (registry credential/GPG failure), so only the skip path and the guard
  behaviour were exercised here. The first real run will be CI's.
- Micrometer timers around every phase of the per-frame decision path
  (`decode`, `facedetect`, `onnxscore`, `heuristicscore`, `padheuristic`,
  `paddonx`, plus a wall-clock `total`), surfaced as a `pipelineTimings` block
  on the existing `GET /api/v1/metrics` with count / mean / max / p99 and each
  phase's share of the measured phases. Only `micrometer-core` was added
  (version managed by the Spring Boot parent, resolving to the same 1.13.3
  already on the classpath as `micrometer-observation`) — not
  `spring-boot-starter-actuator`, because the service already has its own
  metrics surface. Static holder by design: `ImageUtils` and the scoring
  services are constructed directly in unit tests as well as by Spring, so
  constructor injection would have meant threading a registry through every
  test for no benefit. Phases that never ran are **omitted** rather than
  reported as zero, so "fell back to the heuristic" is distinguishable from
  "never scored".
  **What it revealed** — measured over 12 frames of the real-face fixture,
  ONNX is 70% of pipeline time and the frame is detected *three* times:
  mean per frame is `decode` 21.7 ms, `facedetect` 74.5 ms, `onnxscore`
  126.5 ms, `paddonx` 116.9 ms, `padheuristic` 4.8 ms, request `total`
  442.0 ms. `PassiveScoringService.score()` and `assessPad()` each call
  `model.analyzeFrame(...)`, and that method runs its **own** Haar cascade
  internally (`OnnxMiniFasNetBackend.detectFaces`), so one frame pays for the
  HTTP face gate plus two more cascades and two BGR→RGB conversions on the same
  pixels. `paddonx` costing nearly as much as `onnxscore` is the duplicate work
  showing up in the numbers. The phases also do not sum to `total`: the ~98 ms
  gap is JSON, the session `findById`/`save` and the audit write
- `-Pslim` Maven profile producing a ~92 MB linux-x86_64 jar (down from
  ~209 MB) that still runs the full liveness/PAD pipeline. `org.openpnp:opencv`
  ships natives for eight platforms (109 MB) and `onnxruntime` for four plus a
  54 MB macOS `.dSYM`; a Linux runner and the Docker image load exactly one of
  each. The profile unpacks both jars into `BOOT-INF/classes` with the unused
  platform trees excluded, because spring-boot's `<excludes>` can only drop a
  whole dependency and cannot filter *inside* one. Note the trap it avoids:
  excluding the opencv dependency outright does **not** slim the app, it breaks
  it — the jar holds the Java bindings as well as the natives, so Spring cannot
  introspect beans whose signatures mention `Mat` and startup dies with
  `NoClassDefFoundError: org/opencv/core/Mat`. Removing natives while keeping
  the bindings is the only slimming that is actually safe. The profile also has
  to pin `<mainClass>`, because with a `<classifier>` set spring-boot 3.3.3
  auto-detects `Start-Class: ...loader.launch.JarLauncher`, making the loader
  re-launch itself until `StackOverflowError`. Opt-in only; the default build
  is byte-for-byte unchanged and still carries all seven natives
- Rate limiting (`RateLimitFilter`, no new dependency): session creation is
  capped per client IP (30/min) and frame + challenge-validate submissions
  share a per-session budget (60/10s) — over-limit POSTs get 429 + Retry-After
  before any body parsing or DB work; configurable via
  `mosip.security.rate-limit.*`
- Terminal PAD rejection proven end to end: new committed fixture
  `fixtures/print-attack.jpg` (15×15-blur degradation of `real-face.jpg` —
  one face still detectable, texture heuristic classifies it PRINTED_PHOTO and
  the MiniFASNet model independently flags it as an attack) plus
  `PrintAttackFixtureTest` (fast fixture contract) and a
  `LivenessPipelineIntegrationTest` case that pushes two attack frames over
  real HTTP and asserts the `retry_passive` → confirmed `reject` flow, the
  `presentation_attack:PRINTED_PHOTO` failure reason, the `PAD_REJECTED` audit
  entry, and terminality (a third frame gets 409)
- Browser console can now edit the config policy: a per-workflow policy editor
  (threshold, challenge timeout, min challenges, retries, failure policy,
  enabled flags, challenge types) that loads via the open `GET` and saves via
  admin-key `PUT` — the key lives in the tab's sessionStorage, is masked, and is
  never written to the decision log
- GitHub Actions CI (`.github/workflows/ci.yml`) — runs on every push as **two
  parallel jobs**: unit tests (253, surefire
  `!LivenessPipelineIntegrationTest,!PostgresSchemaTest` filter) and the
  full-app integration suite (10), each with Temurin JDK 17,
  Maven dependency cache, `fail-fast: false` and a least-privilege token; a
  gated third job then packages the Spring Boot executable jar (only when both
  suites pass) and uploads it as the `pad-liveness-backend-jar` artifact
  (7-day retention). The upload step is additionally gated on the push ref
  matching the repository's default branch, so feature-branch pushes still
  prove the jar packages but no longer spend the shared artifact quota on
  commits nobody downloads. The package job now also **boots the jar it just
  built** and asserts `GET /health` returns `engine: available` before anything
  is published — on every branch, not just the default one. The test jobs run
  against `target/classes` with the full `~/.m2` classpath, so nothing could
  catch a packaging-only regression (fat jar missing its OpenCV natives, wrong
  `Start-Class`, truncated repackage): those all shipped green and only broke
  for whoever ran the artifact. `/health` always answers `status: ok`, so the
  200 proves nothing on its own — the assertion is on `engine`, which reflects
  whether the natives actually loaded. Pushing a **version tag (`v*`) now also
  publishes that same jar to a GitHub Release**: the `release` job
  re-packages **with the tag's version stamped in** — `v1.0.1` builds
  as `1.0.1`, so the jar's manifest and the asset name match the
  release instead of reading `1.0.0-SNAPSHOT` — then smoke tests it
  and attaches it with `gh release create` (falling back to
  `gh release upload --clobber` when the release already exists, so a re-pushed
  tag converges instead of failing). It carries `contents: write` scoped to
  that one job — the rest of the workflow stays read-only — and authenticates
  with the automatic per-run `GITHUB_TOKEN`, so no secret must be configured.
  The per-push artifact upload still fires on tag pushes (its guard
  includes `v*`), and the release job builds its own stamped copy —
  CI jobs have no shared filesystem, so the two never exchange files.
  `--notes-start-tag` is deliberately omitted because it errors when there is
  no previous release, which would make the very first tag unpublishable.
  The smoke test moved
  from inline YAML into `scripts/smoke-jar.sh` so both jobs share it and it
  stays runnable locally
- `LivenessPipelineIntegrationTest` — boots the full app with H2 (dev profile)
  on a random port and drives the real pipeline over HTTP with no mocked beans:
  passive pass → PASSED, below-threshold escalation → challenge issued →
  validation stays `continue` inside the 15s window, active-disabled reject →
  FAILED, undecodable frame → 422, plus admin-key config updates and security
  headers on real responses
- The browser's policy-save path is now covered end to end:
  `browserStyleConfigPut_isAcceptedWithTheAdminKey` sends a real preflight
  (`Origin` + `Access-Control-Request-*`) and the console's own same-origin PUT
  with Chrome headers and `X-Admin-API-Key`, then asserts the preflight allows
  the admin header, the PUT is applied *and* audited, the same request without
  the key is 403, and a foreign origin is 403 with no policy change. It needed
  the JDK HTTP client rather than `TestRestTemplate`: `HttpURLConnection`
  silently strips `Origin` and `Access-Control-Request-*` as restricted
  headers, so a preflight written that way is indistinguishable from a plain
  same-origin OPTIONS and the test would pass without exercising CORS at all
- Config changes are now audited and reviewable: every successful
  `PUT /api/v1/config/{workflowType}` writes a `CONFIG_CHANGED` entry **in the
  same transaction as the policy update**, recording only the fields that
  actually moved with their old → new values, `CREATED` vs `UPDATED`, and a
  truncated SHA-256 fingerprint of the admin key (never the key itself).
  Rejected requests (no/invalid key, invalid value) record nothing, so a saved
  policy can never be silent and a rollback can never leave a phantom entry.
  Previously a policy edit was entirely untraceable — `audit_logs.session_id`
  was `NOT NULL`, so an operator-level event could not be recorded at all.
  New `GET /api/v1/config/audit?limit=50` (newest first, open read like the
  other config GETs) plus a **Policy change history** panel in the browser
  console, refreshed automatically after each save. Migration V4 makes
  `audit_logs.session_id` nullable (`NULL` = operator event, never a pipeline
  event) and indexes `created_at`; session trails query by session id, so they
  can never pick one up
- Challenge-window timeout proven end to end: a real-time
  `LivenessPipelineIntegrationTest` case configures a 1s challenge window with
  `maxRetryCount=2` (so the engine's floor is what must keep the challenge
  open), waits out both windows, and drives `continue` → `retry_challenge`
  (fresh challenge, attempt 2, different action) → terminal `reject` with
  `max_retries_exceeded`, asserting the paired CHALLENGE_ISSUED/
  CHALLENGE_FAILED audit entries, the FAILED session and close summary, and a
  409 when the failed challenge is re-validated

### Changed
- **Release assets carry the tag version.** `v1.0.1` now builds as
  `1.0.1` — the pom switched to Maven's CI-friendly `${revision}`
  property (default `1.0.0-SNAPSHOT`, so every other build keeps its
  familiar artifact name), overridden only in the release job. The job
  asserts the stamp landed, because a silent miss would still build
  green and publish a SNAPSHOT-named asset. The attach step's
  `ls | grep` also became a nullglob loop, clearing the last
  actionlint warning
- **CI now builds, boots and publishes both jars.** The package job is a two-leg
  matrix: the full cross-platform jar (~200 MB) and the slim linux-x86_64 one
  (~94 MB). Both legs boot and must report `engine: available` — that is what
  catches a dependency bump which quietly breaks the slim profile's native
  filter, which otherwise ships a jar that boots fine and then rejects every
  frame. The run summary reports both sizes, and `scripts/check-jar-size.sh`
  fails the build when a jar outgrows its budget (245 MiB full, 110 MiB slim):
  a jar that quietly re-adds a platform breaks no test, it just doubles
  artifact storage.
- **Artifact names follow the primary download.** `pad-liveness-backend-jar` is
  now the **slim** jar — it is what CI runners, the Docker image and most
  deployments run, and it is the small one. The cross-platform jar moves to
  `pad-liveness-backend-full-jar`, one click away for macOS/Windows consumers.
  Each leg writes its own name because upload-artifact v4 treats a name as
  immutable across jobs: a shared name fails the second leg with 409.
- **Uploads also fire on `v*` tags** (the default branch used to be the only
  publisher), so a release tag gets a versioned artifact independent of which
  branch is the default. `pull_request` is now a trigger as well: branches run
  the identical gate before merging, and the upload guard keeps PRs — forks
  included — out of artifact storage.
- **The Docker image is built from the slim profile**, and the jar is copied
  with `--chown` rather than a `chown -R` layer that stored the same file twice:
  **620 MB → 294 MB** of layers, measured. The COPY is pinned to the `-slim`
  classifier because a slim build leaves the thin jar beside the repackaged one,
  which made the old `*.jar` glob ambiguous. `scripts/smoke-docker.sh` builds the
  image, boots the container and asserts `/health`, the unprivileged user, and
  reports the layer total — the image path had no coverage at all before, so a
  broken `COPY` or base image shipped green.
- `scripts/smoke-jar.sh` takes `SMOKE_JAR=full|slim` (default `full`), selects
  with globs instead of `ls | grep`, and says which jar it is about to boot.
  Its container sibling asserts `status: ok` rather than `engine: available`:
  Alpine is musl and the OpenCV native is glibc-linked, so a correct image serves
  the API with frame processing unavailable by design.
- Rate-limited requests are now counted per rule and reported by
  `GET /api/v1/metrics`: `rateLimitedRequests` (total refused) with the split
  `rateLimitedSessionCreate` / `rateLimitedFrames`, plus
  `rateLimitAllowedRequests` and `rateLimitRejectionRate`, so an operator can
  see the limiter biting (and which rule bites) from the API instead of container
  logs. The tallies live in a new `RateLimitCounters` component rather than on
  the filter, because a mocked `Filter` bean inside a `@WebMvcTest` slice is
  auto-registered as a filter and swallows every request before it reaches a
  handler — and because the metrics controller should not depend on servlet
  plumbing. These counters are per-process: they start at zero on restart,
  unlike the session metrics in the same payload, which are queried from the
  database; `rateLimitRejectionRate` is `0.0`, never `NaN`, with no traffic
- Rate limiting now works behind Docker port-mapping and reverse proxies:
  `mosip.security.rate-limit.trusted-proxies` (`TRUSTED_PROXIES`, comma-separated
  IPs/CIDRs, **default empty = trust nobody**) lists the peers whose
  `X-Forwarded-For` / `X-Real-IP` may be believed when keying the per-IP session
  budget. Forwarded headers are honoured *only* from a trusted peer, and the
  chain is walked right-to-left so the first untrusted hop is the client — a
  client cannot mint extra budgets (or poison someone else's) by sending the
  headers itself, which is why the default is empty and why listing a range
  containing untrusted addresses is called out as unsafe. Before this, every
  client behind NAT shared one budget, the ceiling documented on the filter.
  Hops may carry a port (`10.0.0.1:51234`) or bracketed IPv6 (`[::1]:443`); junk
  and hostname entries are ignored rather than resolved (no DNS in the request
  path). Frame budgets stay keyed by session id
- The rate limiter now **publishes its budget** instead of only failing: every
  limited route returns `X-RateLimit-Bucket` (`session-create` / `frames`),
  `-Limit`, `-Remaining` and `-Reset` (seconds into the window) on *allowed*
  requests as well as 429s, so a client can pace itself rather than discover the
  limiter through a failure. They are added to CORS
  `Access-Control-Expose-Headers` for a console on another dev port, and
  `Retry-After` stays exclusive to the 429
- The console gained a **Request budget** panel: a live meter per limiter budget
  showing how many submissions remain and when the window resets, counting down
  locally every second (no polling), going amber at ≤25% and red on a 429 with
  its retry time. Verified end to end in a browser against a running service,
  including burning the whole session-create budget to watch it trip
- The console's policy editor no longer loses unsaved edits silently. Loading a
  workflow replaced every field, so switching workflow (or pressing Load)
  threw away a threshold the operator had just tuned with no warning. The form
  is now compared against the last loaded/saved state: an amber "Unsaved changes
  to the \<workflow\> policy" marker appears while edits are pending (and clears
  again if a field is reverted by hand), and switching workflow, Load and
  closing/reloading the tab all confirm first — cancelling snaps the workflow
  select back so the UI matches what is actually loaded. The guard fails closed
  if `confirm()` is unavailable (embedded webviews that block dialogs): the edits
  are kept rather than dropped
- The console no longer leaves the admin key sitting in the page: it used to be
  written straight into the input's value on every load, so the browser's
  password masking was the only thing between the secret and a screenshot, a
  screen share or devtools. The key now lives only in this tab's sessionStorage;
  the field is a typing buffer that is cleared on blur and after every save, a
  hint shows a partial mask (`••••••••••••-9f3`, the same idea as the audit
  trail's key fingerprint) so the operator can still tell which key is loaded,
  and **Forget key** removes it from the tab. Saving is unaffected — the key is
  sent from the stored value, verified against a live server
- The challenge-window floor is now `mosip.liveness.min-challenge-window-ms`
  (default **15 000** — unchanged for production, and hard-clamped to 1 000 ms,
  the smallest `challengeTimeoutMs` the config API accepts). It is the guard
  against a shortened or legacy policy row failing someone who needed a moment,
  so raising it only ever makes the flow more patient. Lowering it is a test
  seam: the e2e timeout case now runs 3 s windows instead of 2 × 15 s, cutting
  that test 34.4 s → 10.0 s and the integration class 54 s → 30 s (93 s → 70 s
  in a cold, isolated CI-job run) with identical assertions. The 15 s default and
  the 1 s clamp stay pinned by `DecisionEngineServiceTest` (3 new cases, no
  sleeps). Everything else the class spends is real work — ~15 s of OpenCV face
  detection and ONNX scoring across 25 frames plus a ~45 s Spring context load
  (Hibernate DDL 7.8 s, OpenCV natives 6.4 s, Tomcat + beans 7.1 s); Flyway
  cannot replace that DDL on H2 because the migrations are Postgres-specific
  (`TIMESTAMPTZ`), so dev/integration boots keep `ddl-auto: create-drop`
- OpenCV native warm-up now runs on a background `opencv-warmup` daemon thread
  started from `PadLivenessApplication.main()` before `SpringApplication.run()`,
  so the ~65 MB extraction overlaps context refresh instead of running serially
  in front of it. `ensureOpenCvLoaded()` is unchanged as the synchronous
  barrier — it still loads at most once and still blocks until the attempt has
  settled, so `PassiveScoringService` waits exactly as before and
  `isOpenCvAvailable()` (a plain non-blocking read) can never be observed
  mid-warm by a request, because that mandatory singleton settles the flag
  before refresh ends. `openCvAttempted` became volatile since the fast path is
  now read across threads.
  **Measured, and the headline claim did not hold.** Seven interleaved A/B boots
  per variant on the same box: baseline mean 46.47 s / median 46.02 s, warm-up
  mean 47.25 s / median 48.14 s — indistinguishable. The timeline shows the
  load *does* leave the critical path (baseline occupies +35.5 s → +41.5 s,
  warm +0 s → +4.8 s), but the warm thread costs ~4.5 s in CPU contention with
  the main thread on 4 cores, netting ~1.5 s in the best pair. The original
  "~6 s" figure came from a cold page cache; warm, the load is only ~3.8 s and
  there is nothing to hide. It may still pay off on cold-cache CI runners, which
  could not be verified here (dropping caches needs root). Kept because it is
  behaviour-neutral and correct, not because it is proven faster.
  Two findings worth keeping: logback **silently drops** events logged from the
  warm thread, because it starts before Spring Boot initialises its logging
  system — so the outcome is now reported once from `ensureOpenCvLoaded()`'s
  caller (a configured context) and the warm thread loads silently, otherwise
  the "OpenCV native library loaded successfully" line vanishes from packaged
  builds. That line now carries the measured millisecond cost.

### Security
- `PUT /api/v1/config/{workflowType}` now requires an `X-Admin-API-Key` header
  matching `MOSIP_ADMIN_API_KEY` — fail-closed: config updates are refused when
  no key is configured, so unauthenticated callers can no longer lower the
  liveness threshold and defeat PAD
- Added `SecurityHeadersFilter`: `X-Content-Type-Options`, `X-Frame-Options`,
  `Referrer-Policy`, `Cross-Origin-Opener-Policy`, `Permissions-Policy` and a
  strict `Content-Security-Policy` (`script-src 'self'`, `style-src 'self'`,
  `frame-ancestors 'none'`) on every response (Swagger UI exempt from CSP only)
- POST/PUT bodies over `MAX_REQUEST_BODY_BYTES` (default 24 MB) are rejected
  with 413 before reaching a controller; frame DTOs gained `@Size` caps
- Frame decoding now enforces payload and dimension caps (decompression-bomb
  guard) and releases leaked `Mat` buffers on the error paths
- Fixed CORS: `allowedOriginPatterns` (the previous `allowedOrigins` wildcard
  ports never matched any real origin), methods limited to GET/POST/PUT,
  credentials off
- Python service: replaced wildcard `allow_origins=["*"]` + credentials with an
  origin allow-list (`settings.CORS_ORIGINS`), added the same security headers
  and a body-size cap
- Containers: image runs as non-root, JVM capped (`MaxRAMPercentage=50`,
  `UseSerialGC`, `ExitOnOutOfMemoryError`), PostgreSQL published on loopback
  only; `server.error.include-stacktrace/message: never`

### Fixed
- Invalid path parameters (e.g. a non-UUID `sessionId`) returned 500 — now 400
  `VALIDATION_ERROR` via `MethodArgumentTypeMismatchException` handling
- `DeviceAdapterTest` race: `mockDeviceCorruptFrames` and
  `mockDeviceLifecycleAndFrameDelivery` asserted exact event counts after their
  latches opened while capture keeps producing events until `stopCapture()` —
  they now assert the guaranteed lower bound (flaked under load)

### Performance
- Console script/style moved to `static/console.js` / `static/console.css`
  (cacheable, enables the strict CSP; no inline scripts or styles remain)
- Frames larger than 1280 px are downscaled before analysis so 1080p/4K cameras
  cost the same CPU as 640x480 (low-end hosts)
- Console: single reused capture canvas instead of one per frame, bounded
  decision-log size, health polling paused while the tab is hidden
- Server: Hikari pool 10 → 5, Tomcat threads 200 → 50, response gzip enabled

## [Unreleased] - 2026-10-01

### Fixed
- Resolved intermittent test failures in DeviceAdapterTest
- All test suites now pass consistently
- Improved test reliability for device adapter components
