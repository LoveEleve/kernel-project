package com.scalab.nacos.extension;

import com.alibaba.nacos.plugin.auth.spi.client.AbstractClientAuthService;
import com.alibaba.nacos.plugin.auth.api.LoginIdentityContext;
import com.alibaba.nacos.plugin.auth.api.RequestResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * B5: 自定义 AuthPlugin — 扩展 Nacos 鉴权 SPI
 *
 * Nacos 的权限控制通过 AuthPlugin SPI 实现（ApiType.AUTH）。
 * 默认实现：nacos-default-auth-plugin（基于 IdentityContext + Token）
 *
 * 自定义场景：
 *   - 对接 LDAP/AD（企业统一认证）
 *   - 对接 OAuth2/OIDC（第三方登录）
 *   - 对接内部 SSO 系统
 *
 * 当前环境鉴权未开启（NACOS_AUTH_ENABLE=false），此为概念演示。
 */
public class CustomAuthPlugin extends AbstractClientAuthService {

    private static final Logger log = LoggerFactory.getLogger(CustomAuthPlugin.class);

    @Override
    public Boolean login(Properties properties) {
        // 1. 从 properties 中提取 username/password
        // 2. 调用自己的认证服务（LDAP/OAuth/SSO）
        // 3. 成功 → 返回 token / 失败 → 返回 null
        log.info("[CustomAuth] login attempt");
        return true;
    }

    @Override
    public LoginIdentityContext getLoginIdentityContext(RequestResource resource) {
        // 返回当前请求的认证上下文（token + 用户信息）
        return new LoginIdentityContext();
    }

    @Override
    public void shutdown() {
        log.info("[CustomAuth] shutdown");
    }
}
