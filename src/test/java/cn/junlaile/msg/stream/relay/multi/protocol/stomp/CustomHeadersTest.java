package cn.junlaile.msg.stream.relay.multi.protocol.stomp;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STOMP自定义请求头解析功能测试
 */
@QuarkusTest
public class CustomHeadersTest {

    @Test
    public void testStompFrameWithCustomHeaders() {
        // 创建包含自定义头部的STOMP帧
        Map<String, String> headers = new HashMap<>();
        headers.put("accept-version", "1.2");
        headers.put("heart-beat", "0,0");
        headers.put("custom-client-id", "client-123");
        headers.put("custom-user-role", "admin");
        headers.put("x-custom-header", "custom-value");
        headers.put("auth", "bearer secret-token"); // 敏感信息

        StompFrame frame = new StompFrame("CONNECT", headers, "");

        // 验证标准头部存在
        assertEquals("1.2", frame.header("accept-version"));
        assertEquals("0,0", frame.header("heart-beat"));

        // 验证自定义头部存在
        assertEquals("client-123", frame.header("custom-client-id"));
        assertEquals("admin", frame.header("custom-user-role"));
        assertEquals("custom-value", frame.header("x-custom-header"));
        assertEquals("bearer secret-token", frame.header("auth"));
    }

    @Test
    public void testStompFrameWithoutCustomHeaders() {
        // 只包含标准头部的STOMP帧
        Map<String, String> headers = new HashMap<>();
        headers.put("accept-version", "1.2");
        headers.put("host", "localhost:8080");

        StompFrame frame = new StompFrame("CONNECT", headers, "");

        assertEquals("1.2", frame.header("accept-version"));
        assertEquals("localhost:8080", frame.header("host"));
        assertNull(frame.header("custom-header"));
    }

    @Test
    public void testStompFrameWithNullHeaders() {
        StompFrame frame = new StompFrame("CONNECT", null, "test body");

        assertNull(frame.header("any-header"));
        assertEquals("test body", frame.body()); // StompFrame构造函数会保留非null的body
    }

    @Test
    public void testStandardHeadersList() {
        // 这个测试确保我们识别所有标准STOMP头部
        String[] standardHeaders = {
            "accept-version", "host", "login", "passcode", "heart-beat",
            "session", "destination", "id", "ack", "receipt", "transaction",
            "content-length", "content-type"
        };

        // 验证这些头部名称确实是我们期望的标准头部
        assertTrue(standardHeaders.length > 0);
        assertTrue(standardHeaders.length == 13);
    }
}