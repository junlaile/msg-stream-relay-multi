package cn.junlaile.msg.stream.relay.multi.protocol.common;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;

/**
 * 通用的消息转换工具类
 * 提供各种消息格式之间的转换功能
 */
public class MessageConverter {

    /**
     * 将任意对象转换为 Vert.x Buffer
     *
     * @param payload 要转换的对象
     * @return Buffer 对象，如果 payload 为 null 则返回空 Buffer
     */
    public static Buffer toVertxBuffer(Object payload) {
        if (payload == null) {
            return Buffer.buffer();
        }
        if (payload instanceof Buffer buffer) {
            return buffer;
        }
        if (payload instanceof io.vertx.mutiny.core.buffer.Buffer mutinyBuffer) {
            return mutinyBuffer.getDelegate();
        }
        if (payload instanceof byte[] bytes) {
            return Buffer.buffer(bytes);
        }
        if (payload instanceof String text) {
            return Buffer.buffer(text, StandardCharsets.UTF_8.name());
        }
        if (payload instanceof JsonObject json) {
            return Buffer.buffer(json.encode());
        }
        if (payload instanceof JsonArray array) {
            return Buffer.buffer(array.encode());
        }
        return Buffer.buffer(payload.toString(), StandardCharsets.UTF_8.name());
    }

    /**
     * 将任意对象转换为 Mutiny Buffer
     *
     * @param payload 要转换的对象
     * @return Mutiny Buffer 对象
     */
    public static io.vertx.mutiny.core.buffer.Buffer toMutinyBuffer(Object payload) {
        Buffer vertxBuffer = toVertxBuffer(payload);
        return io.vertx.mutiny.core.buffer.Buffer.newInstance(vertxBuffer);
    }

    /**
     * 将 Buffer 转换为字节数组
     *
     * @param buffer Buffer 对象
     * @return 字节数组，如果 buffer 为 null 则返回空数组
     */
    public static byte[] toByteArray(Buffer buffer) {
        if (buffer == null) {
            return new byte[0];
        }
        return buffer.getBytes();
    }

    /**
     * 将 Buffer 转换为字符串
     *
     * @param buffer Buffer 对象
     * @return 字符串，使用 UTF-8 编码
     */
    public static String toString(Buffer buffer) {
        if (buffer == null) {
            return "";
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /**
     * 将字符串转换为 Buffer
     *
     * @param text 文本字符串
     * @return Buffer 对象
     */
    public static Buffer fromString(String text) {
        if (text == null) {
            return Buffer.buffer();
        }
        return Buffer.buffer(text, StandardCharsets.UTF_8.name());
    }

    /**
     * 将字节数组转换为 Buffer
     *
     * @param bytes 字节数组
     * @return Buffer 对象
     */
    public static Buffer fromByteArray(byte[] bytes) {
        if (bytes == null) {
            return Buffer.buffer();
        }
        return Buffer.buffer(bytes);
    }

    /**
     * 安全地从 JsonObject 中获取字符串值
     *
     * @param json JsonObject 对象
     * @param key 键名
     * @return 字符串值，如果不存在或为空则返回 null
     */
    public static String safeGetString(JsonObject json, String key) {
        if (json == null || key == null) {
            return null;
        }
        String value = json.getString(key);
        return (value != null && !value.isBlank()) ? value : null;
    }

    /**
     * 安全地从 JsonObject 中获取字符串值，支持多个键名
     *
     * @param json JsonObject 对象
     * @param keys 键名数组，按优先级顺序
     * @return 第一个找到的非空字符串值，如果都不存在则返回 null
     */
    public static String safeGetString(JsonObject json, String... keys) {
        if (json == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            String value = safeGetString(json, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 判断字符串是否为空或仅包含空白字符
     *
     * @param value 字符串值
     * @return true 如果字符串为 null、空或仅包含空白字符
     */
    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 判断字符串是否非空且包含非空白字符
     *
     * @param value 字符串值
     * @return true 如果字符串非 null 且包含非空白字符
     */
    public static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private MessageConverter() {
        // 工具类，不允许实例化
    }
}
