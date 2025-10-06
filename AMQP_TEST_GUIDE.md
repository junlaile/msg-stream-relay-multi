# AMQP 协议测试指南

本指南提供了测试 localhost:5673 AMQP 1.0 端点的完整解决方案。

## 服务状态检查

首先确保 Quarkus 服务正在运行：
```bash
python3 simple_check.py
```

或直接检查健康状态：
```bash
curl http://localhost:15674/api/health
```

## 测试方法

### 方法 1：直接 RabbitMQ 测试（推荐）

这种方法直接连接到后端 RabbitMQ，绕过 AMQP 1.0 中继。

**发送消息：**
```bash
python3 amqp_producer_simple.py 5
```

**接收消息：**
```bash
python3 amqp_consumer_simple.py
```

### 方法 2：AMQP 1.0 中继测试

这种方法测试 AMQP 1.0 端点，需要安装 proton-python。

**安装依赖：**
```bash
pip3 install python-qpid-proton
```

**发送消息：**
```bash
python3 amqp_topic_producer.py 5
```

**接收消息：**
```bash
python3 amqp_topic_consumer.py
```

**简化版消费者：**
```bash
python3 amqp_simple_consumer.py
```

### 方法 3：混合测试

1. 使用方法 1 发送消息到 RabbitMQ
2. 使用方法 2 尝试通过 AMQP 1.0 中继接收

## 配置信息

- **AMQP 1.0 端点**: `localhost:5673`
- **Exchange**: `amq.topic`
- **Routing Key**: `topic.test`
- **后端 RabbitMQ**: `www.junlaile.cn:5672`
- **HTTP API**: `http://localhost:15674`

## 故障排除

### 如果 AMQP 1.0 连接失败：

1. **检查服务状态**：
   ```bash
   python3 simple_check.py
   ```

2. **验证配置**：
   - 确认 `application.properties` 中 `relay.amqp.endpoint.enabled=true`
   - 确认端口 `5673` 未被占用

3. **使用直接连接**：
   ```bash
   python3 amqp_producer_simple.py 3
   python3 amqp_consumer_simple.py
   ```

### 如果 RabbitMQ 连接失败：

1. **检查网络连接**：
   ```bash
   telnet www.junlaile.cn 5672
   ```

2. **验证凭据**：
   - 用户名: `admin`
   - 密码: `cdjj2021!@#`
   - 虚拟主机: `/`

## 脚本说明

| 脚本 | 协议 | 用途 | 依赖 |
|------|------|------|------|
| `amqp_producer_simple.py` | AMQP 0.9.1 | 直接发送消息到 RabbitMQ | pika |
| `amqp_consumer_simple.py` | AMQP 0.9.1 | 直接从 RabbitMQ 接收消息 | pika |
| `amqp_topic_producer.py` | AMQP 1.0 | 通过中继发送消息 | python-qpid-proton |
| `amqp_topic_consumer.py` | AMQP 1.0 | 通过中继接收消息 | python-qpid-proton |
| `amqp_simple_consumer.py` | AMQP 1.0 | 简化版中继消费者 | python-qpid-proton |
| `simple_check.py` | - | 检查服务状态 | 无 |

## 推荐测试流程

1. **启动服务**：
   ```bash
   mvn quarkus:dev
   ```

2. **验证服务**：
   ```bash
   python3 simple_check.py
   ```

3. **发送测试消息**：
   ```bash
   python3 amqp_producer_simple.py 5
   ```

4. **接收消息**：
   ```bash
   python3 amqp_consumer_simple.py
   ```

5. **测试 AMQP 1.0 中继**（可选）：
   ```bash
   python3 amqp_simple_consumer.py
   ```

## 预期输出

成功运行时，你应该看到：

- **生产者**：显示消息发送成功
- **消费者**：显示接收到的消息详情，包括：
  - Exchange 和 Routing Key
  - 消息 ID 和时间戳
  - 消息内容
  - 应用属性

如果遇到问题，请参考故障排除部分或检查服务日志。