# 消息流中继服务

一个基于 Quarkus 的高性能消息中继服务，支持 STOMP over WebSocket 协议与 RabbitMQ 之间的消息转发。

## 🌟 特性

- ✅ **STOMP over WebSocket** - Web 浏览器直接连接 RabbitMQ
- ✅ **多种队列模式** - 支持广播和负载均衡
- ✅ **自动重连** - 连接断开自动恢复
- ✅ **健康检查** - 实时监控服务状态
- ✅ **灵活的目标地址** - 支持队列和交换机
- ✅ **统一后端协议** - 使用 RabbitMQ 原生 AMQP 0.9.1 协议（5672端口）

## 🚀 快速开始

### 启动服务

```bash
# 开发模式（支持热重载）
mvn quarkus:dev

# 服务将在 http://localhost:15674 启动
```

### 连接到 WebSocket STOMP

```javascript
import StompJs from '@stomp/stompjs';

const client = new StompJs.Client({
    brokerURL: 'ws://localhost:15674/ws',
    onConnect: () => {
        console.log('已连接');
        
        // 订阅队列
        client.subscribe('/queue/test', (message) => {
            console.log('收到:', message.body);
        });
        
        // 发送消息
        client.publish({
            destination: '/queue/test',
            body: 'Hello World!'
        });
    }
});

client.activate();
```

### 检查服务状态

```bash
# 健康检查
curl http://localhost:15674/health

# 查看队列映射
curl http://localhost:15674/queue-mappings
```

## 🎯 架构说明

本服务作为消息中继，前端使用 STOMP over WebSocket 连接，后端统一使用 RabbitMQ 原生的 AMQP 0.9.1 协议（5672端口）与 RabbitMQ 交互。

```
前端 (STOMP/WebSocket) → 中继服务 → RabbitMQ (AMQP 0.9.1)
```

## 📖 文档

### 入门文档
- **[QUICK-START.md](QUICK-START.md)** - 快速启动指南
- **[PROJECT-STRUCTURE.md](PROJECT-STRUCTURE.md)** - 项目结构说明

### 架构文档
- **[AMQP-ARCHITECTURE.md](AMQP-ARCHITECTURE.md)** - 协议架构说明

### 开发文档
- **[README-REFACTORING.md](README-REFACTORING.md)** - 重构完成报告
- **[REFACTORING-SUMMARY.md](REFACTORING-SUMMARY.md)** - 详细重构总结
- **[CHANGELOG.md](CHANGELOG.md)** - 变更日志

## 🎯 支持的协议

### STOMP over WebSocket

- **前端协议**: STOMP 1.0, 1.1, 1.2 over WebSocket
- **后端协议**: RabbitMQ AMQP 0.9.1（5672端口）
- **WebSocket 端点**: `ws://localhost:15674/ws`
- **目标格式**:
  - `/queue/queue-name` - 直接队列
  - `/exchange/exchange-name/routing-key` - 交换机路由

**示例**:
```javascript
// 订阅队列
client.subscribe('/queue/orders', handler);

// 订阅交换机
client.subscribe('/exchange/logs/info', handler);

// 发送消息
client.publish({destination: '/queue/orders', body: 'data'});
```

### 未来支持

- **AMQP 1.0 over TCP** - 计划中，参考 [AMQP-ARCHITECTURE.md](AMQP-ARCHITECTURE.md)
- **MQTT** - 计划中

## 🔧 配置

### 最小配置（application.properties）

```properties
# HTTP 端口
quarkus.http.port=15674

# RabbitMQ 连接（AMQP 0.9.1 协议）
relay.rabbitmq.host=localhost
relay.rabbitmq.port=5672
relay.rabbitmq.username=admin
relay.rabbitmq.password=password
relay.rabbitmq.virtual-host=/
```

## 🏗️ 项目结构

```
src/main/java/.../multi/
├── protocol/              # 协议层
│   ├── common/           # 公共组件
│   │   ├── Destination.java
│   │   ├── DestinationParser.java
│   │   └── MessageConverter.java
│   └── stomp/           # STOMP 协议
│       ├── StompFrame.java
│       ├── StompFrameDecoder.java
│       └── StompRelayEndpoint.java
├── rabbit/              # RabbitMQ 管理
│   └── RabbitMQClientManager.java
├── config/              # 配置
│   └── RabbitMQConfig.java
└── support/             # 支持工具
    ├── HealthCheckResource.java
    ├── QueueMappingManager.java
    └── QueueMappingResource.java
```

详细说明：[PROJECT-STRUCTURE.md](PROJECT-STRUCTURE.md)

## 🛠️ 开发

### 编译和测试

```bash
# 编译
mvn clean compile

# 运行测试
mvn clean test

# 打包
mvn clean package

# 运行打包后的应用
java -jar target/quarkus-app/quarkus-run.jar
```

### API 端点

| 端点 | 协议/方法 | 说明 |
|------|----------|------|
| `/ws` | WebSocket | STOMP 协议端点 |
| `/health` | GET | 健康检查 |
| `/queue-mappings` | GET | 查看队列映射 |

### 后端通信

中继服务与 RabbitMQ 使用原生 AMQP 0.9.1 协议（5672端口）通信，无需额外配置。

## 📊 队列模式

### 广播模式（默认）

每个客户端独立队列，所有客户端都收到消息：

```javascript
client.subscribe('/exchange/notifications/user.123', handler);
// 每个客户端创建独立的临时队列
```

### 共享模式（负载均衡）

多个客户端共享队列，消息负载均衡分发：

```javascript
client.subscribe('/exchange/tasks/process', handler, {
    'x-queue-mode': 'shared'
});
// 所有客户端共享同一个队列
```

## ✨ 使用示例

### 示例 1：实时通知

```javascript
// 订阅用户通知
client.subscribe('/queue/notifications.user.' + userId, (msg) => {
    showNotification(msg.body);
});
```

### 示例 2：任务队列

```javascript
// 工作者订阅任务（负载均衡）
client.subscribe('/exchange/tasks/process', (msg) => {
    processTask(JSON.parse(msg.body));
}, {
    'x-queue-mode': 'shared'
});

// 发布任务
client.publish({
    destination: '/exchange/tasks/process',
    body: JSON.stringify({type: 'email', to: 'user@example.com'})
});
```

### 示例 3：日志收集

```javascript
// 订阅所有日志
client.subscribe('/exchange/logs/#', (msg) => {
    console.log('日志:', msg.body);
});

// 发送日志
client.publish({
    destination: '/exchange/logs/info',
    body: 'Application started'
});
```

## 🔍 故障排查

### 无法连接 WebSocket

1. 确认服务已启动：`curl http://localhost:15674/health`
2. 检查端口是否被占用
3. 查看防火墙设置

### 无法连接 RabbitMQ

1. 确认 RabbitMQ 服务运行中
2. 检查 `application.properties` 配置
3. 验证用户名密码正确
4. 检查网络连接

### 消息收发异常

1. 确认目标地址格式正确
2. 检查 RabbitMQ 中队列是否存在
3. 查看服务器日志
4. 验证消息格式

详细排查：[QUICK-START.md](QUICK-START.md#故障排查)

## 🚧 技术栈

- **Quarkus 3.28.1** - 高性能 Java 框架
- **Vert.x** - 响应式工具包
- **Vert.x RabbitMQ Client** - RabbitMQ AMQP 0.9.1 客户端
- **WebSocket** - 实时通信
- **STOMP 协议** - 消息传递协议
- **JUnit 5 + Mockito** - 测试框架

## 📈 性能特点

- 异步非阻塞 I/O
- 自动连接池管理
- 消息批处理支持
- 低延迟消息转发
- 自动故障恢复

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

### 开发流程

1. Fork 项目
2. 创建特性分支：`git checkout -b feature/amazing-feature`
3. 提交更改：`git commit -m 'Add amazing feature'`
4. 推送分支：`git push origin feature/amazing-feature`
5. 提交 Pull Request

### 代码规范

- 遵循 Java 编码规范
- 添加适当的注释和文档
- 编写单元测试
- 确保所有测试通过

## 📝 许可证

本项目采用 [MIT License](LICENSE)

## 📞 联系方式

- 提交 Issue：[GitHub Issues](https://github.com/your-repo/issues)
- 讨论区：[GitHub Discussions](https://github.com/your-repo/discussions)

## 🙏 致谢

感谢以下开源项目：

- [Quarkus](https://quarkus.io/)
- [Eclipse Vert.x](https://vertx.io/)
- [RabbitMQ](https://www.rabbitmq.com/)
- [SmallRye](https://smallrye.io/)

---

**最后更新**: 2025-10-05  
**版本**: 1.0-SNAPSHOT  
**状态**: ✅ 生产就绪  
**协议**: STOMP over WebSocket → RabbitMQ AMQP 0.9.1