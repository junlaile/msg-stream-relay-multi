package cn.junlaile.msg.stream.relay.multi.stomp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import jakarta.annotation.Nonnull;

/**
 * 表示 STOMP 协议帧的不可变数据结构，包含命令、头部和主体
 */
public record StompFrame(String command, Map<String, String> headers, String body) {

    /**
     * 创建 STOMP 协议帧
     *
     * @param command STOMP 命令（如 CONNECT, SEND, SUBSCRIBE 等），不能为 null
     * @param headers 头部信息的键值对映射，可以为 null（将被视为空 Map）
     * @param body 消息体内容，可以为 null（将被转换为空字符串）
     * @throws NullPointerException 如果 command 参数为 null
     */
    public StompFrame(String command, Map<String, String> headers, String body) {
        this.command = Objects.requireNonNull(command, "command");
        Map<String, String> copy = new LinkedHashMap<>();
        if (headers != null) {
            copy.putAll(headers);
        }
        this.headers = Collections.unmodifiableMap(copy);
        this.body = body == null ? "" : body;
    }

    /**
     * 根据头部名称获取对应的头部值
     *
     * @param name 头部名称
     * @return 头部值，如果不存在则返回 null
     */
    public String header(String name) {
        return headers.get(name);
    }

    /**
     * 返回 STOMP 帧的字符串表示形式
     *
     * @return 包含命令、头部和消息体的格式化字符串，永远不为 null
     */
    @Nonnull
    @Override
    public String toString() {
        return "StompFrame{" +
                "command='" + command + '\'' +
                ", headers=" + headers +
                ", body='" + body + '\'' +
                '}';
    }
}
