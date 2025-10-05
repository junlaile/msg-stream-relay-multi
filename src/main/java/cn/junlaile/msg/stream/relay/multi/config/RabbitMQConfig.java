package cn.junlaile.msg.stream.relay.multi.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * RabbitMQ 连接配置接口
 * 从配置文件中读取 relay.rabbitmq 前缀的配置项
 */
@ConfigMapping(prefix = "relay.rabbitmq")
public interface RabbitMQConfig {

    /**
     * 获取 RabbitMQ 服务器主机地址
     *
     * @return 主机地址，默认值为 "localhost"
     */
    @WithDefault("localhost")
    String host();

    /**
     * 获取 RabbitMQ 服务器端口号
     *
     * @return 端口号，默认值为 5672
     */
    @WithDefault("5672")
    int port();

    /**
     * 获取 RabbitMQ 虚拟主机名称
     *
     * @return 虚拟主机路径，默认值为 "/"
     */
    @WithDefault("/")
    String virtualHost();

    /**
     * 获取连接 RabbitMQ 的用户名
     *
     * @return 用户名，默认值为 "guest"
     */
    @WithDefault("guest")
    String username();

    /**
     * 获取连接 RabbitMQ 的密码
     *
     * @return 密码，默认值为 "guest"
     */
    @WithDefault("guest")
    String password();

    /**
     * 是否启用自动恢复功能
     * 启用后，连接断开时会自动尝试重新连接
     *
     * @return true 表示启用自动恢复，默认值为 true
     */
    @WithDefault("true")
    boolean automaticRecovery();

    /**
     * 获取请求的心跳间隔时间（秒）
     * 客户端和服务器之间定期发送心跳以检测连接是否存活
     *
     * @return 心跳间隔秒数，默认值为 30 秒
     */
    @WithDefault("30")
    int requestedHeartbeatSeconds();

    /**
     * 获取网络恢复重试间隔时间（秒）
     * 连接断开后，等待此时间后尝试重新连接
     *
     * @return 网络恢复间隔秒数，默认值为 5 秒
     */
    @WithDefault("5")
    int networkRecoveryIntervalSeconds();

    /**
     * 是否启用 SSL/TLS 加密连接
     *
     * @return true 表示启用 SSL 连接，默认值为 false
     */
    @WithDefault("false")
    boolean ssl();
}
