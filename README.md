# 消息流中继服务

基于 Quarkus 的高性能消息中继服务，支持 STOMP over WebSocket 与 RabbitMQ 之间的消息转发。

## 特性

- ✅ **STOMP over WebSocket** - Web 浏览器直接连接 RabbitMQ
- ✅ **多种队列模式** - 支持广播和负载均衡
- ✅ **自动重连** - 连接断开自动恢复
- ✅ **健康检查** - 实时监控服务状态
- ✅ **灵活的目标地址** - 支持队列和交换机

## 快速开始

### 启动服务

```bash
# 开发模式（支持热重载）
mvn quarkus:dev

# 服务将在 http://localhost:15674 启动
```

### 检查服务状态

```bash
# 健康检查
curl http://localhost:15674/api/health

# 队列映射统计
curl http://localhost:15674/api/queue-mapping/stats
```

## 架构

```
前端 (STOMP/WebSocket) → 中继服务 → RabbitMQ (AMQP 0.9.1)
```

## 配置

```properties
# HTTP 端口
quarkus.http.port=15674

# RabbitMQ 连接
relay.rabbitmq.host=localhost
relay.rabbitmq.port=5672
relay.rabbitmq.username=admin
relay.rabbitmq.password=password
relay.rabbitmq.virtual-host=/
```

## 目标地址格式

### 队列格式
```javascript
/queue/queue-name
```

### 交换机格式
```javascript
/exchange/exchange-name/routing-key
```

## 队列模式

### 广播模式（默认）
每个客户端独立队列，所有客户端都收到消息。

### 共享模式
多个客户端共享队列，消息负载均衡分发：
```javascript
client.subscribe('/exchange/tasks/process', handler, {
    'x-queue-mode': 'shared'
});
```

## API 端点

| 端点 | 说明 |
|------|------|
| `/ws` | STOMP WebSocket 端点 |
| `/api/health` | 健康检查 |
| `/api/queue-mapping/stats` | 队列映射统计 |

## 开发

```bash
# 编译
mvn clean compile

# 运行测试
mvn clean test

# 打包
mvn clean package
```

## 技术栈

- **Quarkus 3.28.1** - 高性能 Java 框架
- **Vert.x** - 响应式工具包
- **RabbitMQ Client** - AMQP 0.9.1 客户端
- **WebSocket** - 实时通信
- **STOMP 协议** - 消息传递协议

---

**版本**: 1.0-SNAPSHOT
**状态**: ✅ 生产就绪
