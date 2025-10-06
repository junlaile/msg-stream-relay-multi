# AMQP 1.0 语义重构 Roadmap

> 目的：将当前“create_sender = 订阅 / create_receiver = 发送”的临时倒置语义，逐步演进为符合 AMQP 1.0 规范（Receiver Link = 客户端接收；Sender Link = 客户端发送），并增强可靠性、可观测性和可扩展能力。

---
## 当前状态 (Baseline)
- 服务端类：`AmqpRelayEndpoint`
- 下行（RabbitMQ → 客户端）推送触发点：`senderOpenHandler`
- 上行（客户端 → RabbitMQ）入口：`receiverOpenHandler` + `handleInboundMessage`
- 动态队列策略：为 Exchange 订阅创建 auto-delete 临时队列（带映射缓存）
- 共享模式：目前未真正启用（`isShared()` 恒 false）
- Backpressure：简单基于固定 prefetch/flow（未依据客户端 credit 精细调节）
- 链路日志：已添加 debug 行（`[AMQP] receiverOpen` / `[AMQP] senderOpen`）

问题 / 风险：
1. 语义与 AMQP 生态习惯不一致，客户端容易误用。
2. Receiver link 未用于真正订阅，后续扩展（例如 QoS、selector、共享订阅）受限。
3. 无显式 capability / property 协商（shared、global、lifetime-policy 等）。
4. 缺少可观测性：没有暴露订阅数、队列绑定、滞留消息等指标。
5. 流控只在创建时一次性授予 credits，未与发送端吞吐绑定。
6. 错误处理：订阅创建失败仅关闭 link，未返回明确 error condition 描述或建议。

---
## 重构目标
| 目标 | 描述 | 成功判定 |
|------|------|----------|
| 语义对齐 | create_receiver => 订阅；create_sender => 发布 | 使用标准 Qpid Proton 示例可直接收发 |
| 明确错误 | 解析失败/绑定失败返回标准 AMQP error condition | 客户端收到 `amqp:not-found` / `amqp:invalid-field` 等 |
| 动态队列策略可配置 | 支持独占 / 自动删除 / 持久化 / 前缀策略 | application.properties 新增前缀/模式项 |
| 共享订阅初步实现 | 识别 capabilities: `shared` / `global` | 多客户端共享一个绑定队列，消息负载均衡 |
| 流控优化 | 基于 link credit 动态调节 RabbitMQ consumer 速率 | 压测中无积压且无 OOM |
| 观测增强 | 暴露订阅、队列、重试、滞留指标（Micrometer） | /metrics 出现相关指标 |
| 可扩展抽象 | 将发布/订阅动作封装为独立策略类 | 后续添加过滤/selector 无需改主类 |

---
## 分阶段实施
### Phase 1：诊断与双语义兼容层
- 引入“语义模式”配置：`relay.amqp.semantic-mode=legacy|standard`（默认 legacy 保持当前行为）
- 在 standard 模式下：
  - receiverOpen -> 订阅；senderOpen -> 发布
  - legacy 模式路径保持不变，便于回退
- 添加链接建模：`LinkRole { INBOUND_PUBLISH, OUTBOUND_SUBSCRIBE }`
- 输出清晰日志：`[AMQP][mode=legacy] senderOpen as SUBSCRIBE` / `[AMQP][mode=standard] receiverOpen as SUBSCRIBE`

### Phase 2：标准订阅通道实现
- receiverOpen 中：
  - 解析 remoteSource / target 地址
  - 创建 RabbitMQ consumer → 将 RabbitMQ 投递映射为 Proton `sender` 所需的传输形式
  - 使用单一 link 发送（需要验证 vertx-proton 是否允许在 receiver link 上反向 send；若不允许：显式要求客户端附带 link capability 声明，否则 fallback）
- 若框架受限：在 receiverOpen 回复 redirect error condition，指导客户端建立 sender link（临时策略）。

### Phase 3：共享订阅 & 队列生命周期
- 判断 capabilities (`shared`, `global`) 或 properties（`distribution-mode=move`）→ 使用共享缓存 key
- 共享队列名称策略：`amq.relay.shared.<hash(exchange+routing)>`
- 增加配置：`relay.amqp.shared-queue-prefix`, `relay.amqp.auto-delete-shared=true|false`
- 生命周期：最后一个订阅者离线后延迟 N 秒自动删除（调度器实现）

### Phase 4：流控与背压
- 监听 link credit 变化：若 credit < 阈值暂停 RabbitMQ consumer（`basicCancel` 或内建流速限制）
- 恢复 credit 后重新 `basicConsume`
- 暂存/投递策略：内存环形缓冲 + 丢弃/阻塞模式可选

### Phase 5：可观测性与诊断
- Micrometer 指标：
  - `amqp_relay_active_subscriptions`
  - `amqp_relay_rabbitmq_bindings`
  - `amqp_relay_outbound_messages_total`
  - `amqp_relay_dropped_messages_total`
  - `amqp_relay_backpressure_events_total`
- 日志增强：订阅建立/解绑/异常路径统一 JSON 结构（便于集中分析）

### Phase 6：错误语义 & 健壮性
- 映射错误：
  - 目标地址空 → `amqp:invalid-field`
  - 交换机不存在 → `amqp:not-found`
  - 权限拒绝 → `amqp:unauthorized-access`
- 自定义扩展 condition：`relay:queue-bind-failed`, `relay:internal-error`

### Phase 7：扩展与清理
- 插件式 destination resolver（SPI / Strategy）
- Selector / 头过滤（先决条件：消息属性补齐映射）
- 移除 legacy 模式（发布 release note 后）

---
## 关键技术校验点
| 项 | 需要验证 | 可能结论 | 方案 |
|----|----------|----------|------|
| receiver link 反向发送 | vertx-proton 是否允许 | 不允许 | 需要客户端双 link 或保留临时倒置语义 |
| 动态取消与恢复 | RabbitMQ consumer 取消/恢复时序 | 消费重入延迟 | 增加指数退避/最大重试 |
| 共享队列一致性 | 多实例部署时是否命名冲突 | 可能冲突 | 引入实例 ID / hash 前缀 |

---
## 配置草案
```properties
# 语义模式：legacy（当前：sender=订阅）或 standard（目标：receiver=订阅）
relay.amqp.semantic-mode=legacy

# 共享订阅相关（Phase 3）
relay.amqp.shared-queue-prefix=amq.relay.shared
relay.amqp.auto-delete-shared=true
relay.amqp.shared-idle-ttl-seconds=60

# 流控相关（Phase 4）
relay.amqp.flow.min-credit-threshold=5
relay.amqp.flow.resume-credit-threshold=20
relay.amqp.flow.buffer-size=1000
relay.amqp.flow.buffer-overflow-policy=drop-oldest  # drop-oldest|drop-new|block
```

---
## 风险与回滚策略
| 风险 | 缓解 | 回滚办法 |
|------|------|----------|
| 标准模式逻辑缺陷导致订阅中断 | 双模式开关（默认 legacy） | 配置改回 legacy 并重启 |
| 引入共享队列后队列残留 | 延迟删除 + 周期清理任务 | 清理脚本 / 管理控制台手工删除 |
| 流控误判降吞吐 | 指标对比、可调阈值 | 调大阈值或禁用流控配置 |

---
## 后续指标 (DoD)
- [ ] standard 模式：测试脚本使用 create_receiver 正常收到消息
- [ ] legacy 模式向后兼容（原脚本不改仍可运行）
- [ ] 交换 / 队列 / 路由异常返回对应 AMQP error condition
- [ ] Micrometer 指标在 /q/metrics 可见且含标签（exchange、routingKey、mode）
- [ ] 至少 1 轮高并发（>5k msgs/min）压测无内存泄漏和明显积压

---
## 现行建议（改造完成前）
- 文档中明确：订阅需使用 sender link（已更新 README / AGENTS）
- 对外暴露 `semantic-mode` 计划，提前通知可能切换
- 客户端 SDK 或示例脚本中添加模式检测（可发一条探测帧判断服务端响应 header）

---
如需我开始 Phase 1（加入 semantic-mode 配置与双路径判定）请告知。