# Repository Guidelines

## Project Structure & Module Organization
This service is a single Quarkus module focused on bridging STOMP clients and RabbitMQ brokers. Source lives under `src/main/java/cn/junlaile/msg/stream/relay/multi`, with domain packages: `config` for connection wiring, `support` for REST helpers, `stomp` for frame handling, and `rabbit` for broker clients. Place additional relay logic alongside the nearest package to keep ownership clear. Shared constants go in a new `support` subpackage; avoid creating parallel module trees unless you add Maven submodules. Runtime config and queue mappings belong in `src/main/resources/application.properties`.

## Build, Test, and Development Commands
- `mvn quarkus:dev` — start hot-reload dev mode with live reload and STOMP endpoint.
- `mvn compile` — compile against Java 21 to validate changes quickly.
- `mvn test` — run unit/integration suites via Surefire and Quarkus JUnit 5.
- `mvn package -DskipTests` — build the runnable JAR for deployment; omit tests only when CI already passed.

## Coding Style & Naming Conventions
Follow standard Java style with 4-space indentation, UTF-8 source encoding, and class-per-file organization. Use PascalCase for types, camelCase for members, and uppercase snake for constants. Prefer constructor or CDI injection over field injection to align with Quarkus best practices. Keep REST paths and queues descriptive and kebab-case (e.g., `/queue-mappings`). JSON payloads should remain camelCase to match current endpoints.

## Testing Guidelines
Tests belong in `src/test/java` mirroring the production package path. New tests should extend JUnit 5, using `@QuarkusTest` for wiring and Mockito for broker stubs. Name classes with the `*Test` suffix; reserve `*IT` for long-running integration flows. Focus coverage on `QueueMappingManager`, `StompRelayEndpoint`, and health endpoints before adding new functionality.

## Commit & Pull Request Guidelines
Git history favors concise, present-tense summaries (e.g., `stomp协议转发`). Use one-line subjects under 50 characters, optionally in Chinese, and skip ending punctuation. PRs should include a problem statement, highlights of changed endpoints or queues, config impacts, and manual test notes or curl examples where relevant. Link issues or tasks when available and attach any screenshots demonstrating client connectivity.

## Messaging Configuration Tips
Document new broker hosts, credentials, and queue mappings directly in `application.properties`, and call them out in PR descriptions so operators can update environment secrets promptly.
