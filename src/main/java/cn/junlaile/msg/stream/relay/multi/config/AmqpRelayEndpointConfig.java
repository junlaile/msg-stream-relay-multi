package cn.junlaile.msg.stream.relay.multi.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * AMQP 1.0 中继端点配置，控制监听地址、端口以及是否启用。
 */
@ConfigMapping(prefix = "relay.amqp.endpoint")
public interface AmqpRelayEndpointConfig {

    /**
     * 是否启用 AMQP 1.0 中继端点。
     *
     * @return true 时在启动时监听端口
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * 监听主机地址。
     *
     * @return 主机名或 IP，默认 0.0.0.0
     */
    @WithDefault("0.0.0.0")
    String host();

    /**
     * 监听端口号。
     *
     * @return 端口号，默认 5673
     */
    @WithDefault("5673")
    int port();

    /**
     * 客户端空闲超时时间（秒）。
     *
     * @return 超时秒数，默认 60
     */
    @WithDefault("60")
    int idleTimeoutSeconds();

    /**
     * 每个发送端默认授予的信用数，用于流控。
     *
     * @return 信用数，默认 50
     */
    @WithDefault("50")
    int initialCredits();
}
