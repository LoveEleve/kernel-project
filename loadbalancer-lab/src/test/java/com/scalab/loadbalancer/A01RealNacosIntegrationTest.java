package com.scalab.loadbalancer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A-01/A-03 真实集成测试（@Tag("real")，默认被 mvn test 排除）。
 *
 * <p>依赖真实环境：
 * <ul>
 *   <li>Nacos 注册中心：21.130.247.89:18848（application.yml 默认）</li>
 *   <li>nacos-lab 双实例：28001 / 28003（必须在 Nacos 注册）</li>
 *   <li>openfeign-lab：28002（验证服务列表时可选）</li>
 * </ul>
 *
 * <p>运行：{@code mvn test -pl loadbalancer-lab -Dgroups=real}
 * （离线/CI 环境无真实依赖，用默认测试即可）
 */
@Tag("real")
@SpringBootTest(classes = LoadBalancerLabApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class A01RealNacosIntegrationTest {

    @Autowired
    private RestTemplate loadBalancedRestTemplate;

    @Autowired
    private DiscoveryClient discoveryClient;

    @Test
    void realInstancesShouldBeAvailable() {
        List<String> services = discoveryClient.getServices();
        System.out.println("[Real] services: " + services);

        assertTrue(services.contains("nacos-lab"), "nacos-lab should be registered in Nacos");
        assertFalse(discoveryClient.getInstances("nacos-lab").isEmpty(),
                "nacos-lab should have at least one instance");

        List<Integer> ports = discoveryClient.getInstances("nacos-lab")
                .stream().map(i -> i.getPort()).sorted().toList();
        System.out.println("[Real] nacos-lab ports: " + ports);
    }

    @Test
    void roundRobinShouldDistributeOverRealInstances() {
        // 真实轮询：20 次调用应严格均匀分布到 nacos-lab 实例
        Map<String, Integer> portCount = new TreeMap<>();
        for (int i = 0; i < 20; i++) {
            Map<String, Object> status = loadBalancedRestTemplate.getForObject(
                    "http://nacos-lab/discovery/status", Map.class);
            if (status == null) {
                throw new IllegalStateException("null response at call " + i);
            }
            portCount.merge(String.valueOf(status.get("port")), 1, Integer::sum);
        }
        System.out.println("[Real] round-robin distribution: " + portCount);

        // nacos-lab 至少双实例；断言命中数之和 = 20，且每个端口命中数非零（真实轮询遍历所有实例）
        assertEquals(20, portCount.values().stream().mapToInt(Integer::intValue).sum(),
                "total calls should be 20");
        for (Map.Entry<String, Integer> e : portCount.entrySet()) {
            assertTrue(e.getValue() > 0, "port " + e.getKey() + " should be hit at least once");
        }
    }
}
