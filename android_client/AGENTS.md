# Android client integration fragments

This directory contains a Flutter/Android build harness for the MOSIP Registration Client integration. It is not the full Registration Client: the downstream host still supplies production session collaborators, runtime camera permission handling, and registration/auth wiring. Use [`README.md`](README.md) for build commands, host wiring, and supported integration versions.

- Preserve the documented R1–R5 invariants: fail closed; never send camera frames over Pigeon; let the liveness orchestrator select challenges; keep network activity out of liveness decisions; and expose only generic, localized UI messages.
- Keep the Pigeon schema, generated host/Flutter API expectations, Dart view model, and Android bridge behavior aligned. Never add frame payloads, private keys, or model internals to the channel contract.
- Keep Android framework and CameraX code inside the host-integration layer. Shared state-machine and policy behavior belongs in the root Java engine; the Flutter build compiles the client bridge and required engine sources, while the Maven suite tests the engine behavior.
- Keep `pubspec.lock`, the Gradle wrapper, and generated Pigeon outputs in sync. Use the pinned Flutter version and `tool/generate_pigeon.sh`; CI checks generated output and builds a debug APK.
- The Android build harness checks compilation, not device behavior. Do not claim camera, keystore, registration capture, or host lifecycle integration has been validated without a host/device run.
- When changing integration prerequisites or setup steps, update this directory's README and distinguish verified host requirements from assumptions that still need host-project validation.
