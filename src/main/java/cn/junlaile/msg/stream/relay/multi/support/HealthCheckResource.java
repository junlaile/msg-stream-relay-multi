package cn.junlaile.msg.stream.relay.multi.support;

import cn.junlaile.msg.stream.relay.multi.rabbit.RabbitMQClientManager;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查端点，提供 RabbitMQ 连接状态和系统健康信息
 * 用于监控和告警系统集成
 */
@Path("/api/health")
public class HealthCheckResource {

    private final RabbitMQClientManager clientManager;
    private final QueueMappingManager queueMappingManager;

    /**
     * 构造健康检查资源端点
     *
     * @param clientManager RabbitMQ 客户端管理器实例
     * @param queueMappingManager 队列映射管理器实例
     */
    @Inject
    public HealthCheckResource(RabbitMQClientManager clientManager,
                               QueueMappingManager queueMappingManager) {
        this.clientManager = clientManager;
        this.queueMappingManager = queueMappingManager;
    }

    /**
     * 获取系统健康状态
     * 返回 200 OK 表示健康，503 Service Unavailable 表示不健康
     *
     * @return HTTP 响应，包含健康状态详情
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getHealth() {
        Map<String, Object> health = new HashMap<>();

        // RabbitMQ 连接状态
        boolean rabbitmqConnected = clientManager.getClient().isConnected();
        boolean rabbitmqHealthy = clientManager.isConnectionHealthy();
        long lastSuccessfulOp = clientManager.getLastSuccessfulOperationTime();
        long reconnectAttempts = clientManager.getReconnectAttempts();

        Map<String, Object> rabbitmqStatus = new HashMap<>();
        rabbitmqStatus.put("connected", rabbitmqConnected);
        rabbitmqStatus.put("healthy", rabbitmqHealthy);
        rabbitmqStatus.put("lastSuccessfulOperation", Instant.ofEpochMilli(lastSuccessfulOp).toString());
        rabbitmqStatus.put("reconnectAttempts", reconnectAttempts);
        rabbitmqStatus.put("status", rabbitmqConnected && rabbitmqHealthy ? "UP" : "DOWN");

        // 队列映射统计
        QueueMappingManager.CacheStats stats = queueMappingManager.getStats();
        Map<String, Object> queueMappingStatus = new HashMap<>();
        queueMappingStatus.put("destinationCount", stats.destinationCount());
        queueMappingStatus.put("ruleCount", stats.ruleCount());
        queueMappingStatus.put("totalQueues", stats.totalQueues());
        queueMappingStatus.put("activeQueues", stats.activeQueues());
        queueMappingStatus.put("idleQueues", stats.idleQueues());
        queueMappingStatus.put("status", "UP");

        // 整体状态
        boolean overallHealthy = rabbitmqConnected && rabbitmqHealthy;
        health.put("status", overallHealthy ? "UP" : "DOWN");
        health.put("rabbitmq", rabbitmqStatus);
        health.put("queueMapping", queueMappingStatus);
        health.put("timestamp", Instant.now().toString());

        // 根据健康状态返回相应的 HTTP 状态码
        if (overallHealthy) {
            return Response.ok(health).build();
        } else {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(health).build();
        }
    }

    /**
     * 获取 RabbitMQ 连接的详细状态
     *
     * @return RabbitMQ 连接详情
     */
    @GET
    @Path("/rabbitmq")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRabbitMQHealth() {
        Map<String, Object> rabbitmqHealth = new HashMap<>();

        boolean connected = clientManager.getClient().isConnected();
        boolean healthy = clientManager.isConnectionHealthy();
        long lastSuccessfulOp = clientManager.getLastSuccessfulOperationTime();
        long reconnectAttempts = clientManager.getReconnectAttempts();
        long timeSinceLastOp = System.currentTimeMillis() - lastSuccessfulOp;

        rabbitmqHealth.put("connected", connected);
        rabbitmqHealth.put("healthy", healthy);
        rabbitmqHealth.put("lastSuccessfulOperation", Instant.ofEpochMilli(lastSuccessfulOp).toString());
        rabbitmqHealth.put("timeSinceLastOperationMs", timeSinceLastOp);
        rabbitmqHealth.put("reconnectAttempts", reconnectAttempts);
        rabbitmqHealth.put("status", connected && healthy ? "UP" : "DOWN");
        rabbitmqHealth.put("timestamp", Instant.now().toString());

        if (connected && healthy) {
            return Response.ok(rabbitmqHealth).build();
        } else {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(rabbitmqHealth).build();
        }
    }

    /**
     * 简单的活性检查端点
     * 用于负载均衡器和容器编排系统
     *
     * @return 200 OK 表示应用运行中
     */
    @GET
    @Path("/liveness")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getLiveness() {
        return Response.ok("OK").build();
    }

    /**
     * 就绪检查端点
     * 检查应用是否准备好接收流量
     *
     * @return 200 OK 表示就绪，503 表示未就绪
     */
    @GET
    @Path("/readiness")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getReadiness() {
        boolean ready = clientManager.getClient().isConnected()
                     && clientManager.isConnectionHealthy();

        if (ready) {
            return Response.ok("READY").build();
        } else {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity("NOT_READY").build();
        }
    }
}
