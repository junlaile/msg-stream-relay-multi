package cn.junlaile.msg.stream.relay.multi.support;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * REST 端点，提供队列映射缓存的统计信息
 * 用于监控和调试队列映射管理器的状态
 */
@Path("/api/queue-mapping")
public class QueueMappingResource {

    private final QueueMappingManager queueMappingManager;

    /**
     * 构造队列映射资源端点
     *
     * @param queueMappingManager 队列映射管理器实例
     */
    @Inject
    public QueueMappingResource(QueueMappingManager queueMappingManager) {
        this.queueMappingManager = queueMappingManager;
    }

    /**
     * 获取队列映射缓存的统计信息
     * 包括目标数量、规则数量、总队列数、活跃队列数和空闲队列数
     *
     * @return 缓存统计信息的 JSON 对象
     */
    @GET
    @Path("/stats")
    @Produces(MediaType.APPLICATION_JSON)
    public QueueMappingManager.CacheStats getStats() {
        return queueMappingManager.getStats();
    }
}
