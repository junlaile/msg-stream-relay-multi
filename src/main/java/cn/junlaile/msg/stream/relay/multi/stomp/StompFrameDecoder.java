package cn.junlaile.msg.stream.relay.multi.stomp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解码器类，负责将字节流或字符串解析为 StompFrame 对象，
 * 支持 STOMP 协议的帧解析逻辑
 */
public final class StompFrameDecoder {

    private final StringBuilder accumulator = new StringBuilder();

    /**
     * 将新的数据块追加到累加器并尝试解析完整的 STOMP 帧
     *
     * @param chunk 新接收的字符串数据块，可以为 null 或空字符串
     * @return 已解析完成的 STOMP 帧列表，可能为空列表但不会为 null
     */
    public List<StompFrame> append(String chunk) {
        if (chunk != null && !chunk.isEmpty()) {
            accumulator.append(chunk);
        }
        List<StompFrame> frames = new ArrayList<>();
        int delimiter;
        while ((delimiter = accumulator.indexOf("\0")) >= 0) {
            String raw = accumulator.substring(0, delimiter);
            accumulator.delete(0, delimiter + 1);
            if (raw.isEmpty()) {
                continue;
            }
            frames.add(parseSingle(raw));
        }
        return frames;
    }

    /**
     * 解析单个 STOMP 原始帧字符串
     *
     * @param rawFrame 原始帧字符串，不包含结尾的 NULL 字符
     * @return 解析后的 StompFrame 对象
     * @throws IllegalArgumentException 如果帧格式无效或缺少命令
     */
    private StompFrame parseSingle(String rawFrame) {
        String normalized = rawFrame.replace("\r", "");
        int headerBoundary = normalized.indexOf("\n\n");
        String headerPart = headerBoundary >= 0 ? normalized.substring(0, headerBoundary) : normalized;
        String body = headerBoundary >= 0 ? normalized.substring(headerBoundary + 2) : "";
        String[] headerLines = headerPart.split("\n");
        if (headerLines.length == 0 || headerLines[0].isBlank()) {
            throw new IllegalArgumentException("Invalid STOMP frame: missing command");
        }
        String command = headerLines[0].trim();
        Map<String, String> headers = getHeaders(headerLines);
        return new StompFrame(command, headers, body);
    }

    /**
     * 从头部行数组中提取头部键值对
     *
     * @param headerLines 包含命令和头部信息的字符串数组，第一行是命令
     * @return 头部信息的键值对 Map，不包含命令行，可能为空 Map 但不会为 null
     */
    private static Map<String, String> getHeaders(String[] headerLines) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < headerLines.length; i++) {
            String line = headerLines[i];
            if (line == null || line.isEmpty()) {
                continue;
            }
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = line.substring(0, idx).trim();
            String value = line.substring(idx + 1).trim();
            headers.put(key, value);
        }
        return headers;
    }
}
