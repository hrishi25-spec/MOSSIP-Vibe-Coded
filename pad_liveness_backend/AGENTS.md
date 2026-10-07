# Python liveness backend

These rules apply only to the FastAPI service in this directory.

- Keep this service's routes, schemas, database models, and configuration self-contained. Coordinate intentionally when a public contract shared with the Spring service or clients changes; do not assume both backends have identical internals.
- PostgreSQL is the service database. The in-memory SQLite API tests verify request behavior without PostgreSQL, camera hardware, or real model inference; do not treat them as a PostgreSQL migration check.
- Keep model inference behind the existing liveness/PAD engine interfaces, and keep API responses free of model internals and sensitive frame data.
- Use environment-based configuration and test-only credentials. Do not commit live secrets or biometric samples.
- Install test dependencies from `requirements-dev.txt` and run `python -m pytest -q` from this directory. The behavioral tests use an isolated database and stub model engines.
- Follow this directory's README for local service setup and database commands; do not apply the root Maven build instructions to this service.
