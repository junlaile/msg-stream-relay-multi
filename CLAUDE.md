# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a **Message Stream Relay Service** (消息流中继服务) built with Quarkus 3.28.1 and Java 21. It enables communication between STOMP over WebSocket clients and RabbitMQ via AMQP 0.9.1 protocol, with additional support for AMQP 1.0 clients.

**Architecture:**
```
Frontend (STOMP/WebSocket) → Relay Service → RabbitMQ (AMQP 0.9.1)
                            ↘ AMQP 1.0 Clients ↗
```

## Development Commands

```bash
# Development with hot reload
mvn quarkus:dev

# Build commands
mvn clean compile
mvn clean test
mvn clean package
mvn clean package -DskipTests

# Service endpoints (when running)
curl http://localhost:15674/api/health
curl http://localhost:15674/api/queue-mapping/stats

# Debug mode
mvn quarkus:dev -Ddebug=5005

# Development UI
curl http://localhost:15674/q/dev-ui
```

**Service URLs:**
- Main service: `http://localhost:15674`
- STOMP WebSocket: `/ws`
- Health check: `/api/health`
- Queue stats: `/api/queue-mapping/stats`
- AMQP 1.0 endpoint: `amqp://localhost:5673`

## Architecture & Key Components

**Core Package Structure:**
- `cn.junlaile.msg.stream.relay.multi.config` - Configuration classes
- `cn.junlaile.msg.stream.relay.multi.protocol` - Protocol handlers
  - `stomp` - STOMP over WebSocket implementation
  - `amqp` - AMQP 1.0 endpoint implementation
  - `common` - Shared protocol utilities
- `cn.junlaile.msg.stream.relay.multi.rabbit` - RabbitMQ client management
- `cn.junlaile.msg.stream.relay.multi.support` - Supporting utilities

**Key Classes:**
1. **StompRelayEndpoint** (`/ws`) - Main WebSocket endpoint handling STOMP protocol
2. **AmqpRelayEndpoint** - AMQP 1.0 protocol endpoint
3. **RabbitMQClientManager** - RabbitMQ connection lifecycle management
4. **QueueMappingManager** - Three-tier queue mapping cache system
5. **HealthCheckResource** - REST health check endpoints
6. **QueueMappingResource** - Queue management REST API
7. **StompFrameDecoder** - Custom STOMP frame parsing and validation
8. **DestinationParser** - Destination address parsing and validation
9. **MessageConverter** - Message format conversion between protocols

## Queue Management System

The service supports two queue modes:
- **Broadcast mode (default):** Each client gets independent queue
- **Shared mode:** Multiple clients share queue (load balancing)

**Destination Formats:**
- Queue: `/queue/queue-name`
- Exchange: `/exchange/exchange-name/routing-key`

## Configuration

**Main Config:** `src/main/resources/application.properties`
- HTTP port: 15674
- RabbitMQ connection settings
- Auto-recovery enabled
- Dev Services disabled
- AMQP 1.0 endpoint configuration

**Key Config Classes:**
- `RabbitMQConfig` - RabbitMQ connection parameters
- `AmqpRelayEndpointConfig` - AMQP endpoint settings

**Environment Variables:**
All configuration properties support environment variable override with the pattern:
- `relay.rabbitmq.host` → `RELAY_RABBITMQ_HOST`
- `quarkus.http.port` → `QUARKUS_HTTP_PORT`
- `relay.amqp.enabled` → `RELAY_AMQP_ENABLED`

## Testing

- Tests mirror production package structure in `src/test/java/`
- Use `@QuarkusTest` for integration tests
- Focus on core components: `QueueMappingManager`, `StompRelayEndpoint`, health endpoints
- Current test coverage: One integration test for AMQP endpoint
- Test utilities available in `cn.junlaile.msg.stream.relay.multi.support`

**Testing Commands:**
```bash
# Run all tests
mvn clean test

# Run specific test class
mvn test -Dtest=AmqpRelayEndpointTest

# Run with coverage
mvn clean verify jacoco:report
```

## Code Style Guidelines

- Standard Java with 4-space indentation
- UTF-8 encoding
- PascalCase for types, camelCase for members
- Constructor/CDI injection preferred over field injection
- Use `@ApplicationScoped` for singleton services
- Follow Quarkus best practices for reactive programming

## Performance Considerations

- Use reactive patterns with Vert.x `Future` and `CompositeFuture`
- Implement proper backpressure handling
- Leverage the three-tier cache system in `QueueMappingManager`
- Use connection pooling for RabbitMQ operations
- Monitor memory usage with WebSocket connections

## Debugging and Troubleshooting

**Common Issues:**
1. **RabbitMQ Connection Failures**: Check `RabbitMQClientManager` logs and verify connection parameters
2. **WebSocket Connection Issues**: Verify STOMP frame format in `StompFrameDecoder`
3. **Queue Mapping Problems**: Check `QueueMappingManager` cache state
4. **AMQP 1.0 Issues**: Verify `AmqpRelayEndpoint` configuration

**Debug Tools:**
- Use `curl http://localhost:15674/q/dev` for development info
- Check logs for `QueueMappingManager` cache statistics
- Monitor WebSocket connections through browser dev tools
- Use RabbitMQ Management UI for queue inspection

## Git Conventions

- Concise, present-tense summaries (Chinese allowed)
- One-line subjects under 50 characters
- Include problem statements and config impacts in PRs
- Use conventional commit format when possible

**Examples:**
- `fix: 修复 STOMP 帧解析错误`
- `feat: 添加 AMQP 1.0 协议支持`
- `docs: 更新 README 配置说明`

## Dependencies

**Core Dependencies:**
- Quarkus REST (JAX-RS)
- Quarkus WebSockets
- Quarkus RabbitMQ Messaging
- Vert.x STOMP
- Vert.x RabbitMQ Client
- Vert.x Proton (AMQP 1.0)
- Quarkus Micrometer (metrics)
- Quarkus Scheduler

**Testing:**
- JUnit 5 with Quarkus Test
- Mockito for mocking
- Rest Assured for API testing

## Features

1. **Multi-Protocol Support:** STOMP over WebSocket + AMQP 1.0
2. **Automatic Queue Creation:** Dynamic queue and binding creation
3. **Connection Recovery:** Automatic reconnection and subscription recovery
4. **Health Monitoring:** Built-in health check endpoints
5. **Three-tier Caching:** Queue mapping with efficient cache system