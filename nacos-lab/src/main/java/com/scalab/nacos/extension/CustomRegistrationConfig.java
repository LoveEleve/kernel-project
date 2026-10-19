package com.scalab.nacos.extension;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.cloud.nacos.registry.NacosRegistration;
import com.alibaba.cloud.nacos.registry.NacosRegistrationCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.serviceregistry.AutoServiceRegistrationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * B1: 自定义注册配置 — application.yml 中已排除 NacosServiceRegistryAutoConfiguration，
 * 用 CustomServiceRegistry + CustomAutoServiceRegistration 替代默认实现
 */
@Configuration
@ConditionalOnProperty(value = "scalab.nacos.custom-registration.enabled", havingValue = "true")
public class CustomRegistrationConfig {

    private static final Logger log = LoggerFactory.getLogger(CustomRegistrationConfig.class);

    /**
     * 替换 NacosServiceRegistry → CustomServiceRegistry（注入自定义 metadata）
     */
    @Bean
    public CustomServiceRegistry customServiceRegistry(
            NacosServiceManager nacosServiceManager,
            NacosDiscoveryProperties properties) {
        log.info("[CustomConfig] Creating CustomServiceRegistry");
        return new CustomServiceRegistry(nacosServiceManager, properties);
    }

    /**
     * 替换 NacosAutoServiceRegistration → CustomAutoServiceRegistration
     * 构造函数参数与 AbstractAutoServiceRegistration 完全一致
     */
    @Bean
    public CustomAutoServiceRegistration customAutoServiceRegistration(
            CustomServiceRegistry serviceRegistry,
            AutoServiceRegistrationProperties autoServiceRegistrationProperties,
            NacosRegistration registration,
            NacosDiscoveryProperties properties) {
        log.info("[CustomConfig] Creating CustomAutoServiceRegistration");
        return new CustomAutoServiceRegistration(
                serviceRegistry, autoServiceRegistrationProperties,
                registration, properties);
    }

    /**
     * NacosRegistration 仍然需要 — 它提供 host/port/metadata 基础信息
     * CustomAutoServiceRegistration.getRegistration() 返回它
     */
    @Bean
    public NacosRegistration nacosRegistration(
            ObjectProvider<List<NacosRegistrationCustomizer>> registrationCustomizers,
            NacosDiscoveryProperties nacosDiscoveryProperties,
            ApplicationContext context) {
        log.info("[CustomConfig] Creating NacosRegistration for custom use");
        return new NacosRegistration(
                registrationCustomizers.getIfAvailable(),
                nacosDiscoveryProperties, context);
    }
}
