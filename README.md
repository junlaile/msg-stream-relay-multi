# 消息流中继服务 (Message Stream Relay Service)

基于 Quarkus 3.28.1 和 Java 21 的高性能消息中继服务，支持多协议消息转发，实现 STOMP over WebSocket 客户端与 RabbitMQ (AMQP 0.9.1) 之间的通信，并额外支持 AMQP 1.0 客户端。

## 架构概览

```
前端 (STOMP/WebSocket) → 中继服务 → RabbitMQ (AMQP 0.9.1)
                     ↘ AMQP 1.0 客户端 ↗
```

## 核心特性

- ✅ **多协议支持** - STOMP over WebSocket + AMQP 1.0
- ✅ **自动队列创建** - 动态创建队列和绑定
- ✅ **连接恢复** - 自动重连和订阅恢复
- ✅ **健康监控** - 内置健康检查端点
- ✅ **三级缓存** - 高效的队列映射缓存系统
- ✅ **多种队列模式** - 广播模式和共享模式（负载均衡）
- ✅ **灵活的目标地址** - 支持队列和交换机路由

## 快速开始

### 环境要求

- **Java 21+**
- **Maven 3.9+**
- **RabbitMQ 3.12+** (可选，用于生产环境)

### 启动服务

开发模式支持热重载，生产环境需要打包后运行。服务默认运行在端口 15674，提供 STOMP WebSocket 端点 `/ws` 和健康检查端点 `/api/health`。

可使用 curl 命令验证服务状态或查看开发工具端点。

## 配置

### 配置说明

主要配置包括 HTTP 端口（默认 15674）、RabbitMQ 连接参数和 AMQP 1.0 端点设置。支持环境变量覆盖配置，配置项使用 `relay.` 前缀。

## 目标地址格式

### 目标地址格式

- **队列**: `/queue/queue-name`
- **交换机**: `/exchange/exchange-name/routing-key`

## 队列模式

### 队列模式

- **广播模式（默认）**: 每个客户端独立队列，所有客户端都收到消息
- **共享模式**: 多个客户端共享队列，消息负载均衡分发，通过自定义头部 `x-queue-mode: shared` 启用

## API 端点

### WebSocket 端点
| 端点 | 协议 | 说明 |
|------|------|------|
| `/ws` | STOMP over WebSocket | 主要的 WebSocket 端点，支持 STOMP 协议 |

### REST API 端点
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/health` | GET | 健康检查，返回服务状态 |
| `/api/queue-mapping/stats` | GET | 队列映射统计信息 |
| `/api/queue-mapping` | GET | 获取所有队列映射 |
| `/api/queue-mapping/{queueName}` | DELETE | 删除指定队列映射 |

### AMQP 1.0 端点
| 端点 | 协议 | 端口 | 说明 |
|------|------|------|------|
| AMQP 1.0 | AMQP 1.0 | 5673 | 原生 AMQP 1.0 协议支持（当前订阅需使用 sender link，见下文说明）

## 客户端连接

支持 STOMP over WebSocket 客户端（如 JavaScript SockJS + STOMP.js）和 AMQP 1.0 客户端（如 Qpid Proton / Qpid JMS）。客户端可进行消息订阅和发送，支持动态队列创建和绑定。

⚠️ AMQP 1.0 当前语义说明：
- create_sender → 订阅（服务端在 senderOpen 创建 RabbitMQ consumer 并推送）
- create_receiver → 向 RabbitMQ 发送（服务端转发到队列/交换机）
- 后续版本将对齐为标准语义（create_receiver = 订阅）。

地址格式示例：
- 交换机：`/exchange/amq.topic/topic.test`
- 队列：`/queue/my.queue`

## 开发指南

### 开发说明

使用 Maven 进行项目管理，支持编译、测试、打包等标准操作。开发模式提供热重载功能，可启用调试端口（默认 5005）。Quarkus 开发工具提供应用信息查看和监控功能。

## 项目结构

```
src/main/java/cn/junlaile/msg/stream/relay/multi/
├── config/                     # 配置类
│   ├── RabbitMQConfig.java    # RabbitMQ 连接配置
│   └── AmqpRelayEndpointConfig.java # AMQP 端点配置
├── protocol/                   # 协议处理器
│   ├── stomp/                 # STOMP 协议实现
│   ├── amqp/                  # AMQP 1.0 协议实现
│   └── common/                # 通用协议工具
├── rabbit/                     # RabbitMQ 客户端管理
│   └── RabbitMQClientManager.java
├── support/                    # 支持组件
│   ├── QueueMappingManager.java # 队列映射管理
│   ├── HealthCheckResource.java  # 健康检查
│   └── QueueMappingResource.java # 队列管理 API
└── Application.java            # 应用入口
```

## 技术栈

### 核心框架
- **Quarkus 3.28.1** - 云原生 Java 框架
- **Java 21** - 最新 LTS Java 版本
- **Maven** - 项目构建和依赖管理

### 消息和通信
- **Vert.x STOMP** - STOMP 协议支持
- **Vert.x RabbitMQ Client** - RabbitMQ AMQP 0.9.1 客户端
- **Vert.x Proton** - AMQP 1.0 协议支持
- **Quarkus WebSockets** - WebSocket 支持

### 监控和工具
- **Quarkus Micrometer** - 指标收集
- **Quarkus Scheduler** - 定时任务支持

### 测试框架
- **JUnit 5** - 单元测试框架
- **Mockito** - Mock 框架
- **Rest Assured** - REST API 测试

## 性能特性

- **响应式编程** - 基于 Vert.x 事件循环
- **连接池管理** - 高效的 RabbitMQ 连接管理
- **三级缓存系统** - 队列映射缓存优化
- **自动恢复机制** - 连接断开后自动重连
- **背压处理** - 防止消息积压导致内存溢出

## 部署建议

### 部署方式

支持传统部署、容器化部署（Docker）和 Kubernetes 部署。需要暴露端口 15674（HTTP/WebSocket）和 5673（AMQP 1.0）。在容器化环境中可通过环境变量配置 RabbitMQ 连接参数。

---

**版本**: 1.0-SNAPSHOT
**状态**: ✅ 生产就绪
**维护者**: Jun Laile
**许可证**: MIT License
