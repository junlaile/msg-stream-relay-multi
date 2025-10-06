#!/usr/bin/env python3
"""
简单的 AMQP 1.0 消费者 - 专注消费数据
"""

from proton import Message
from proton.handlers import MessagingHandler
from proton.reactor import Container
import signal
import sys
import time
import threading

class SimpleConsumer(MessagingHandler):
    def __init__(self):
        super().__init__()
        self.running = True
        self.count = 0
        self.container = None
        # 设置信号处理
        signal.signal(signal.SIGINT, self._signal_handler)
        signal.signal(signal.SIGTERM, self._signal_handler)

    def _signal_handler(self, signum, frame):
        print(f"\n收到信号 {signum}，正在停止...")
        self.running = False
        if self.container:
            self.container.stop()

    def on_start(self, event):
        print("启动 AMQP 1.0 消费者")
        print("连接到 localhost:5673")
        self.container = event.container

        try:
            # 采用 URI 形式直接创建 receiver，避免先 connect 再创建时的解析/时序问题
            # 订阅 RabbitMQ 内置 topic 交换机 amq.topic，路由键 topic.test
            # AMQP 1.0 中继端点需要使用以 /exchange/ 开头的地址格式
            # 使用 create_receiver(host:port, address) 两段式更贴近 proton 示例
            address = "/exchange/amq.topic/topic.test"
            event.container.create_receiver("127.0.0.1:5673", address)

            print(f"✓ 已订阅: 127.0.0.1:5673{address}")
            print("等待消息... (按 Ctrl+C 退出)\n")
            return  # 直接返回避免下面的通用打印重复

            event.container.create_receiver(address)

            print(f"✓ 已订阅: {address}")
            print("等待消息... (按 Ctrl+C 退出)\n")
        except Exception as e:
            print(f"✗ 连接失败: {e}")
            self.running = False
            event.container.stop()

    def on_message(self, event):
        if not self.running:
            return

        msg = event.message
        self.count += 1
        current_time = time.strftime('%H:%M:%S')

        print(f"📨 消息 #{self.count} [{current_time}]")
        print(f"   ID: {msg.id}")
        if msg.subject:
            print(f"   主题: {msg.subject}")
        if msg.content_type:
            print(f"   类型: {msg.content_type}")
        print(f"   地址: {msg.address}")
        # 自动尝试把 body 解析为常见类型（Binary -> utf-8 文本）
        body = msg.body
        if isinstance(body, memoryview):
            try:
                body_text = body.tobytes().decode('utf-8')
            except Exception:
                body_text = repr(body)
        else:
            body_text = body if isinstance(body, str) else repr(body)
        print(f"   内容: {body_text}")

        if msg.properties:
            print("   应用属性:")
            for k, v in msg.properties.items():
                print(f"     {k}: {v}")
        print("-" * 50)

    def on_connection_opened(self, event):
        print("✓ 连接已建立")

    def on_connection_error(self, event):
        print(f"✗ 连接错误: {event.connection.condition}")
        if hasattr(event.connection.condition, 'description'):
            print(f"   描述: {event.connection.condition.description}")
        self.running = False

    def on_session_error(self, event):
        print(f"✗ 会话错误: {event.session.condition}")
        if hasattr(event.session.condition, 'description'):
            print(f"   描述: {event.session.condition.description}")
        self.running = False

    def on_link_error(self, event):
        print(f"✗ 链路错误: {event.link.condition}")
        if hasattr(event.link.condition, 'description'):
            print(f"   描述: {event.link.condition.description}")
        self.running = False

    def on_disconnected(self, event):
        print("✗ 连接断开")
        self.running = False

    def on_transport_error(self, event):
        print(f"✗ 传输错误: {event.transport.condition}")
        if hasattr(event.transport.condition, 'description'):
            print(f"   描述: {event.transport.condition.description}")
        self.running = False

if __name__ == "__main__":
    print("AMQP 1.0 消费者测试")
    print("=" * 40)

    try:
        consumer = SimpleConsumer()
        container = Container(consumer)
        container.run()

        print("\n消费者已停止")
        print(f"总共接收消息: {consumer.count}")

    except KeyboardInterrupt:
        print("\n\n⚠️ 用户中断")
    except Exception as e:
        print(f"\n❌ 错误: {e}")
        sys.exit(1)