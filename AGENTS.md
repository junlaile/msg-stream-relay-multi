# Repository Guidelines

## Project Structure & Module Organization
This service is a single Quarkus module bridging:
- STOMP over WebSocket → RabbitMQ (AMQP 0.9.1)
- AMQP 1.0 clients → RabbitMQ (via relay endpoint on configurable port, default 5673)

Source path: `src/main/java/cn/junlaile/msg/stream/relay/multi`
Key packages:
- `config` – configuration mappings (RabbitMQ, AMQP endpoint)
- `protocol.stomp` – STOMP endpoint `/ws`
- `protocol.amqp` – AMQP 1.0 relay (`AmqpRelayEndpoint`)
- `protocol.common` – destination parsing, frame conversion
- `rabbit` – RabbitMQ client manager (connection lifecycle, publish, queue ops)
- `support` – REST resources, queue mapping management

AMQP 1.0 当前行为说明 (重要):
- 订阅（从 RabbitMQ 收消息）目前仍通过客户端创建 sender link 实现（服务端在 `senderOpen` 中建立内部 RabbitMQ consumer 并推送）。
- 客户端创建 receiver link 目前表示“上行发送消息到 RabbitMQ”。
- 后续计划：语义对齐（使 create_receiver 直接订阅），需重构 link 方向处理；请在未完成前不要假设标准 AMQP 方向语义。

Put new shared utilities under `protocol.common` or a focused subpackage; avoid premature module splitting.

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

## Messaging & AMQP 1.0 Usage Tips
- Broker host / credentials live in `application.properties` (`relay.rabbitmq.*`).
- AMQP 1.0 relay listens on `relay.amqp.endpoint.port` (default 5673).
- 当前订阅方式：客户端需使用 create_sender("host:port/exchange/EX/RK") 才会收到来自 RabbitMQ 的消息；create_receiver 用于向 Rabbit 发送。
- 发送到交换机：地址格式 `/exchange/<exchange>/<routingKey>`；发送到队列：`/queue/<queue>`。
- 队列共享语义将在后续通过 capabilities 或自定义属性实现；目前默认独立队列（为 exchange 订阅动态声明）。
- 标明变更（特别是 AMQP 1.0 语义调整）在 PR 描述中，避免脚本使用混乱。
