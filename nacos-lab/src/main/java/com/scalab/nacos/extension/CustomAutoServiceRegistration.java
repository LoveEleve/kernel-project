package com.scalab.nacos.extension;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.registry.NacosRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.client.serviceregistry.AbstractAutoServiceRegistration;
import org.springframework.cloud.client.serviceregistry.AutoServiceRegistrationProperties;
import org.springframework.cloud.client.serviceregistry.Registration;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;

/**
 * B1: 自定义自动注册 — 继承 AbstractAutoServiceRegistration，使用 CustomServiceRegistry
 *
 * 与 NacosAutoServiceRegistration 的区别：
 * 1. 不依赖 NacosAutoServiceRegistration 的实现细节
 * 2. 使用我们自己的 CustomServiceRegistry（注入自定义 metadata）
 * 3. getRegistration() 仍用 NacosRegistration（保持标准元数据兼容）
 *
 * 生命周期：
 *   构造 → WebServerInitializedEvent 触发.start() → serviceRegistry.register()
 *       → @PreDestroy 触发 stop() → serviceRegistry.deregister()
 */
public class CustomAutoServiceRegistration
        extends AbstractAutoServiceRegistration<Registration> {

    private static final Logger log = LoggerFactory.getLogger(CustomAutoServiceRegistration.class);

    private final NacosRegistration registration;
    private final NacosDiscoveryProperties properties;

    public CustomAutoServiceRegistration(
            ServiceRegistry<Registration> serviceRegistry,
            AutoServiceRegistrationProperties autoServiceRegistrationProperties,
            NacosRegistration registration,
            NacosDiscoveryProperties properties) {
        super(serviceRegistry, autoServiceRegistrationProperties);
        this.registration = registration;
        this.properties = properties;
    }

    @Override
    protected NacosRegistration getRegistration() {
        // 关键：WebServerInitializedEvent 之后 AbstractAutoServiceRegistration.port 才有值，
        // 需要显式 set 到 NacosRegistration，否则 getPort() 返回 -1 → Nacos 注册失败
        if (this.registration.getPort() < 0 && this.getPort().get() > 0) {
            this.registration.setPort(this.getPort().get());
            log.info("[CustomAutoReg] Port resolved: {}", this.getPort().get());
        }
        return this.registration;
    }

    @Override
    protected void register() {
        // 注册前再次确保端口已解析
        if (this.registration.getPort() < 0) {
            this.registration.setPort(getPort().get());
        }
        log.info("[CustomAutoReg] Registering via CustomServiceRegistry, port={}",
                this.registration.getPort());
        super.register();
    }

    @Override
    protected NacosRegistration getManagementRegistration() {
        return null;
    }

    @Override
    protected boolean isEnabled() {
        return properties.isRegisterEnabled();
    }

    @Override
    protected Object getConfiguration() {
        return properties;
    }
}
