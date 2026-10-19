package com.scalab.loadbalancer;

import com.scalab.loadbalancer.config.BuilderDemoLbConfig;
import com.scalab.loadbalancer.config.HealthCheckLbConfig;
import com.scalab.loadbalancer.config.HintLbConfig;
import com.scalab.loadbalancer.config.RandomLbConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@LoadBalancerClient(name = "nacos-lab", configuration = { RandomLbConfig.class,
        HealthCheckLbConfig.class, HintLbConfig.class, BuilderDemoLbConfig.class })
public class LoadBalancerLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoadBalancerLabApplication.class, args);
    }

    /**
     * A-17: @LoadBalanced RestTemplate（拦截器注入，LoadBalancerInterceptor 路径，D7 分析过）。
     * A-12: 加 connect/read 超时，避免请求无限等待（重试机制依赖异常/超时触发）。
     */
    @Bean
    @LoadBalanced
    public RestTemplate loadBalancedRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        return new RestTemplate(factory);
    }

    /** A-17: @LoadBalanced RestClient（Builder 场景，DeferringLoadBalancerInterceptor 路径，D7 分析过） */
    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    /**
     * A-05: 健康检查专用客户端（非负载均衡！）。
     *
     * <p>health-check 链（D3 的 withBlockingHealthChecks / withBlockingRestClientHealthChecks）
     * 从子上下文取 {@code RestClient} bean 做实例健康探测——如果拿到 @LoadBalanced 客户端，
     * 探测请求会被 LoadBalancerInterceptor 拦截（把实例 IP 当服务名），
     * 导致健康检查死循环、所有实例被过滤（"No servers available for service: 21.214.97.212"）。
     */
    @Bean
    public RestClient plainRestClient() {
        return RestClient.builder().build();
    }
}
