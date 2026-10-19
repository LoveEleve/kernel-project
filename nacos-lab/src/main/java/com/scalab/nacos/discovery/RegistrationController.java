package com.scalab.nacos.discovery;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.registry.NacosAutoServiceRegistration;
import com.alibaba.cloud.nacos.registry.NacosRegistration;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 服务注册 — 生产深度
 *
 * 场景一：临时实例 vs 持久化实例
 *   临时（ephemeral=true）：心跳续约，断联后自动摘除，微服务默认
 *   持久化（ephemeral=false）：不下线，不自动摘除，DB/第三方手动注册
 *
 * 场景二：优雅下线
 *   生产重启流程：/offline → deregister → 等待流量排干 → shutdown
 *   验证方法：调用 /discovery/deregister → 检查 Nacos 控制台实例数 → /discovery/reregister 恢复
 *
 * 场景三：实例生命周期
 *   注册 → 上线 → 标记不健康 → 摘除 → 重新上线
 */
@RestController
public class RegistrationController {

    private static final Logger log = LoggerFactory.getLogger(RegistrationController.class);

    @Autowired(required = false)
    private NacosRegistration registration;

    @Autowired(required = false)
    private DiscoveryClient discoveryClient;

    @Autowired(required = false)
    private NacosAutoServiceRegistration autoServiceRegistration;

    @Autowired(required = false)
    private NacosDiscoveryProperties discoveryProperties;

    @Autowired(required = false)
    private NamingService namingService;

    private volatile boolean markedOffline = false;

    /**
     * 查看当前实例完整状态
     */
    @GetMapping("/discovery/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new HashMap<>();
        result.put("applicationName", discoveryProperties != null
                ? discoveryProperties.getService() : "n/a");
        result.put("ip", discoveryProperties != null
                ? discoveryProperties.getIp() : "n/a");
        result.put("port", discoveryProperties != null
                ? discoveryProperties.getPort() : 0);
        result.put("ephemeral", discoveryProperties != null
                ? discoveryProperties.isEphemeral() : "n/a");
        result.put("clusterName", discoveryProperties != null
                ? discoveryProperties.getClusterName() : "n/a");
        result.put("weight", discoveryProperties != null
                ? discoveryProperties.getWeight() : 0);
        result.put("metadata", discoveryProperties != null
                ? discoveryProperties.getMetadata() : "{}");
        result.put("autoRegistrationRunning", autoServiceRegistration != null
                && autoServiceRegistration.isRunning());
        result.put("markedOffline", markedOffline);
        return result;
    }

    /**
     * 列出所有已注册的服务（含实例详情）
     */
    @GetMapping("/discovery/services")
    public Map<String, Object> services() {
        Map<String, Object> result = new HashMap<>();
        if (discoveryClient != null) {
            List<String> services = discoveryClient.getServices();
            result.put("serviceCount", services.size());
            result.put("services", services);
            result.put("details", services.stream()
                    .collect(Collectors.toMap(s -> s, s -> discoveryClient.getInstances(s))));
        }
        return result;
    }

    /**
     * 场景二：主动摘除注册（模拟上线前对自身摘除）
     *
     * 调这个 → 观察 Nacos 控制台实例数-1 → 等待 10s →
     * 调 /discovery/reregister → 实例恢复
     */
    @PostMapping("/discovery/deregister")
    public Map<String, Object> deregister() {
        Map<String, Object> result = new HashMap<>();
        String action = "deregister";
        try {
            if (autoServiceRegistration != null && autoServiceRegistration.isRunning()) {
                autoServiceRegistration.stop();
                markedOffline = true;
                result.put("result", "ok");
                result.put("action", action);
                result.put("note", "已摘除。10s 后 Nacos 控制台应看不到此实例。调 /discovery/reregister 恢复");
                log.info("[Deregister] 实例已从 Nacos 摘除");
            } else {
                result.put("result", "already_offline");
                result.put("action", action);
                result.put("note", "实例未在注册状态");
            }
        } catch (Exception e) {
            result.put("result", "error");
            result.put("error", e.getMessage());
            log.error("[Deregister] 摘除失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 场景二：重新注册（恢复上线）
     */
    @PostMapping("/discovery/reregister")
    public Map<String, Object> reregister() {
        Map<String, Object> result = new HashMap<>();
        try {
            if (autoServiceRegistration != null && !autoServiceRegistration.isRunning()) {
                autoServiceRegistration.start();
                markedOffline = false;
                result.put("result", "ok");
                result.put("note", "已重新注册。检查 Nacos 控制台确认");
                log.info("[Reregister] 实例已重新注册到 Nacos");
            } else {
                result.put("result", "already_running");
                result.put("note", "实例已在注册状态");
            }
        } catch (Exception e) {
            result.put("result", "error");
            result.put("error", e.getMessage());
            log.error("[Reregister] 重新注册失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 场景三：标记实例不健康（模拟健康检查失败）
     *
     * Nacos Server 收到不健康标记后，该实例不会被分配给调用方
     */
    @PostMapping("/discovery/set-unhealthy")
    public Map<String, Object> setUnhealthy() throws Exception {
        Map<String, Object> result = new HashMap<>();
        if (namingService != null && discoveryProperties != null) {
            String serviceName = discoveryProperties.getService();
            String ip = discoveryProperties.getIp();
            int port = discoveryProperties.getPort();
            String group = discoveryProperties.getGroup();

            namingService.deregisterInstance(serviceName, group, ip, port);
            Instance instance = new Instance();
            instance.setIp(ip);
            instance.setPort(port);
            instance.setHealthy(false);
            namingService.registerInstance(serviceName, group, instance);

            result.put("result", "ok");
            result.put("note", "实例已标记为不健康。调 /discovery/set-healthy 恢复");
        } else {
            result.put("result", "error");
            result.put("error", "namingService 不可用");
        }
        return result;
    }

    /**
     * 恢复健康状态
     */
    @PostMapping("/discovery/set-healthy")
    public Map<String, Object> setHealthy() throws Exception {
        Map<String, Object> result = new HashMap<>();
        if (namingService != null && discoveryProperties != null) {
            String serviceName = discoveryProperties.getService();
            String ip = discoveryProperties.getIp();
            int port = discoveryProperties.getPort();
            String group = discoveryProperties.getGroup();

            namingService.deregisterInstance(serviceName, group, ip, port);
            Instance instance = new Instance();
            instance.setIp(ip);
            instance.setPort(port);
            instance.setHealthy(true);
            namingService.registerInstance(serviceName, group, instance);

            result.put("result", "ok");
            result.put("note", "实例已恢复健康");
        } else {
            result.put("result", "error");
            result.put("error", "namingService 不可用");
        }
        return result;
    }

    /**
     * 场景二：优雅下线钩子（JVM 关闭时自动触发）
     *
     * 验证方法：curl /discovery/deregister → kill 进程 → 检查 Nacos 控制台
     * 正常流程：Spring 容器关闭 → @PreDestroy → deregister → JVM 退出
     */
    @PreDestroy
    public void gracefulShutdown() {
        log.info("=== 优雅下线开始 ===");
        if (autoServiceRegistration != null && autoServiceRegistration.isRunning()) {
            autoServiceRegistration.stop();
            log.info("[GracefulShutdown] 已从 Nacos 摘除");
        }
        // 等待 5s 让摘除通知生效
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("=== 优雅下线完成 ===");
    }

    @GetMapping("/health")
    public String health() {
        return "nacos-lab is running";
    }
}
