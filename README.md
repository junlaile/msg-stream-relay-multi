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

```bash
# 开发模式（支持热重载）
mvn quarkus:dev

# 生产构建
mvn clean package -DskipTests
java -jar target/msg-stream-relay-multi-1.0-SNAPSHOT-runner.jar
```

服务启动后可访问：
- **主服务**: http://localhost:15674
- **STOMP WebSocket**: `/ws`
- **健康检查**: `/api/health`

### 验证服务状态

```bash
# 健康检查
curl http://localhost:15674/api/health

# 队列映射统计
curl http://localhost:15674/api/queue-mapping/stats

# 查看所有可用端点
curl http://localhost:15674/q/dev
```

## 配置

### 基础配置 (application.properties)

```properties
# HTTP 服务端口
quarkus.http.port=15674

# RabbitMQ 连接配置
relay.rabbitmq.host=localhost
relay.rabbitmq.port=5672
relay.rabbitmq.username=admin
relay.rabbitmq.password=password
relay.rabbitmq.virtual-host=/
relay.rabbitmq.connection-timeout=60000
relay.rabbitmq.automatic-recovery-enabled=true

# AMQP 1.0 端点配置
relay.amqp.enabled=true
relay.amqp.host=0.0.0.0
relay.amqp.port=5673

# 开发配置
%dev.quarkus.log.level=DEBUG
%dev.quarkus.rabbitmq.devservices.enabled=false
```

### 环境变量支持

```bash
# 使用环境变量覆盖配置
export QUARKUS_HTTP_PORT=8080
export RELAY_RABBITMQ_HOST=rabbitmq.example.com
export RELAY_RABBITMQ_USERNAME=relay_user
export RELAY_RABBITMQ_PASSWORD=secure_password
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
| AMQP 1.0 | AMQP 1.0 | 5673 | 原生 AMQP 1.0 协议支持 |

## 使用示例

### JavaScript 客户端 (STOMP over WebSocket)

```javascript
// 连接到 STOMP WebSocket
const socket = new SockJS('http://localhost:15674/ws');
const stompClient = Stomp.over(socket);

stompClient.connect({}, function (frame) {
    console.log('Connected: ' + frame);

    // 订阅队列
    stompClient.subscribe('/queue/test-queue', function (message) {
        console.log('Received message:', JSON.parse(message.body));
    });

    // 发送消息
    stompClient.send('/queue/test-queue', {}, JSON.stringify({
        content: 'Hello from client!',
        timestamp: new Date().toISOString()
    }));
});
```

### Java 客户端 (AMQP 1.0)

```java
// 使用 Qpid JMS 客户端连接 AMQP 1.0 端点
JmsConnectionFactory factory = new JmsConnectionFactory("amqp://localhost:5673");
Connection connection = factory.createConnection("admin", "password");
Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

// 发送消息
Queue queue = session.createQueue("test-queue");
MessageProducer producer = session.createProducer(queue);
TextMessage message = session.createTextMessage("Hello from AMQP 1.0 client!");
producer.send(message);
```

## 开发指南

### 构建和测试

```bash
# 编译项目
mvn clean compile

# 运行单元测试
mvn clean test

# 运行集成测试
mvn clean verify

# 打包应用
mvn clean package

# 跳过测试打包（生产环境）
mvn clean package -DskipTests

# 生成本地文档
mvn javadoc:javadoc
```

### 开发工具

```bash
# 启动开发模式（热重载）
mvn quarkus:dev

# 启动调试模式
mvn quarkus:dev -Ddebug=5005

# 查看应用信息
curl http://localhost:15674/q/dev-ui
```

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

### Docker 部署

```dockerfile
FROM fabric8/java-alpine-openjdk21:latest
COPY target/msg-stream-relay-multi-1.0-SNAPSHOT-runner.jar /deployments/app.jar
EXPOSE 15674 5673
CMD ["java", "-jar", "/deployments/app.jar"]
```

### Kubernetes 部署

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: msg-stream-relay
spec:
  replicas: 3
  selector:
    matchLabels:
      app: msg-stream-relay
  template:
    metadata:
      labels:
        app: msg-stream-relay
    spec:
      containers:
      - name: relay
        image: msg-stream-relay:latest
        ports:
        - containerPort: 15674
        - containerPort: 5673
        env:
        - name: RELAY_RABBITMQ_HOST
          value: "rabbitmq-service"
```

---

**版本**: 1.0-SNAPSHOT
**状态**: ✅ 生产就绪
**维护者**: Jun Laile
**许可证**: MIT License
