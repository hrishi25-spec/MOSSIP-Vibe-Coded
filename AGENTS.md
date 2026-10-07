# Repository agent guidance

## Project map

- The root Maven project is the Spring Boot service and shared Java liveness engine. Java source and test rules are in [`src/AGENTS.md`](src/AGENTS.md).
- [`pad_liveness_backend/`](pad_liveness_backend/) is the independent FastAPI/PostgreSQL backend; follow its [`AGENTS.md`](pad_liveness_backend/AGENTS.md).
- [`android_client/`](android_client/) contains a pinned Flutter/Android build harness and MOSIP host-integration fragments, not the full Registration Client; follow its [`AGENTS.md`](android_client/AGENTS.md).
- [`docs/`](docs/) contains repository and integration documentation; follow [`docs/AGENTS.md`](docs/AGENTS.md).
- [`vendor/github setup/`](vendor/github%20setup/) is a separate vendor app with its own [`AGENTS.md`](vendor/github%20setup/AGENTS.md).

## Rules that apply across this repository

- Apply the closest `AGENTS.md`; nested project rules add detail for their own files. Do not carry one project's build assumptions or conventions into another.
- Keep changes focused on the requested work. Inspect existing edits before changing a file, and preserve unrelated work in the shared checkout.
- Keep credentials, private keys, real biometric data, and production identifiers out of source, tests, logs, and documentation.
- When a change crosses a project boundary, keep the affected API or integration contract and its documentation in sync.
- Run the checks documented for the project you changed. Report checks that could not run and why; do not describe an unrun check as passing.
