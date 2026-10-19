# 自定义 AuthPlugin - 扩展 Nacos 鉴权 SPI

## 一、Nacos 鉴权体系的完整架构

### 两层鉴权

Nacos 的权限控制通过 SPI 机制实现,分为**客户端**和**服务端**两层:

```
客户端侧:
  ClientAuthService(SPI 接口)
    ├── login(Properties)         -> 登录获取 Token
    ├── getLoginIdentityContext() -> 每个请求注入 Token
    └── shutdown()                -> 清理资源

服务端侧(Handler filter chain, D-05):
  RequestHandler.handleRequest(request, meta)
    -> filter chain:
       ① AuthFilter(@Secured 注解) -> 从请求头提取 Token -> AuthManager 验证
       ② TpsControlFilter(@TpsControl) -> QPS 限流
       ③ ParamCheckFilter(@ExtractorManager) -> 参数校验
    -> handler.handle() -> 实际业务
```

### 鉴权不在 API 层

NamingService 和 ConfigService 接口没有任何安全注解。鉴权统一集中在**服务端 Handler 层**:
- SDK 保持轻量(不感知鉴权)
- 恶意请求通过 `@TpsControl` 限流 + `ConnectionManager` 连接数限制防御
- 鉴权失败直接拒绝,不到达业务逻辑

## 二、为什么要自定义

| 场景 | 说明 |
|---|---|
| LDAP/AD | 企业 Active Directory 统一认证 |
| OAuth2/OIDC | 第三方登录(GitHub/Google/企业微信) |
| 内部 SSO | 企业单点登录(阿里云 RAM、腾讯云 CAM) |
| JWT 自签发 | 微服务间通过 JWT Token 互信 |

Nacos 默认 `nacos-default-auth-plugin` 基于内置用户名/密码 + Token。如果企业已有统一认证,不应在 Nacos 里再维护一套账号。

## 三、客户端侧: 完整实现

```java
public class LdapAuthService extends AbstractClientAuthService {

    private final LdapTemplate ldapTemplate;
    private volatile String cachedToken;
    private volatile long tokenExpireTime;

    @Override
    public Boolean login(Properties properties) {
        String username = properties.getProperty("username");
        String password = properties.getProperty("password");

        // 调用 LDAP 认证
        try {
            ldapTemplate.authenticate(
                LdapQueryBuilder.query().where("uid").is(username), password);
        } catch (AuthenticationException e) {
            log.warn("LDAP auth failed for user: {}", username);
            return false;
        }

        // 认证通过 -> 向 Nacos Server 申请 Token
        this.cachedToken = requestNacosToken(username, password);
        this.tokenExpireTime = System.currentTimeMillis() + TOKEN_TTL;
        log.info("User {} logged in via LDAP, token expires at {}", username, tokenExpireTime);
        return true;
    }

    @Override
    public LoginIdentityContext getLoginIdentityContext(RequestResource resource) {
        LoginIdentityContext ctx = new LoginIdentityContext();
        // 检查 Token 是否过期
        if (System.currentTimeMillis() > tokenExpireTime) {
            // Token 过期 -> 返回空(服务端会拒绝) -> SDK 自动重新 login
            return ctx;
        }
        ctx.setParameter("accessToken", cachedToken);
        ctx.setParameter("userId", getCurrentUserId());
        return ctx;
    }

    @Override
    public void shutdown() {
        ldapTemplate.getContextSource().getContext().close();
        log.info("LDAP auth service shutdown");
    }
}
```

### SPI 注册

`META-INF/services/com.alibaba.nacos.plugin.auth.spi.client.ClientAuthService`:
```
com.scalab.nacos.extension.auth.LdapAuthService
```

### Spring Cloud 客户端配置

```yaml
spring:
  cloud:
    nacos:
      discovery:
        username: ${LDAP_USER}     # 传给 login(Properties) 的 username
        password: ${LDAP_PASS}     # 传给 login(Properties) 的 password
      config:
        username: ${LDAP_USER}
        password: ${LDAP_PASS}
```

## 四、服务端侧: AuthPluginService

客户端发来的 Token 在请求头 `accessToken` 中。服务端的 `AuthFilter`(Handler filter chain 第一道)提取 Token 并交给 `AuthManager` 验证:

```
gRPC 请求到达
  -> GrpcRequestAcceptor
  -> RequestHandler.handleRequest
    -> AuthFilter.filter(request, meta, handlerClass)
      -> 检查 handlerClass 是否有 @Secured 注解
      -> 有: 提取 Token -> AuthManager.validate(token, resource)
        -> 通过: 继续下一个 filter
        -> 拒绝: 返回 403 Response(不执行 handler.handle())
      -> 无 @Secured: 跳过鉴权(公开接口)
```

服务端实现需要实现 `com.alibaba.nacos.plugin.auth.spi.server.AuthPluginService` SPI（2.3.0 真实接口名；早期版本曾叫 `AuthPlugin`）,并注册到 `META-INF/services/`。具体接口因 Nacos 版本而异,参考官方文档 `nacos-plugin/auth`.

## 五、Token 生命周期

```
客户端启动 -> ClientAuthService.login()
  -> 认证成功 -> Nacos Server 签发 Token(带 TTL)
  -> SDK 缓存 Token

每个 API 调用 -> getLoginIdentityContext()
  -> 注入 Token 到 gRPC metadata

Token 过期 -> getLoginIdentityContext() 返回空
  -> 服务端返回 403
  -> SDK 自动重新调用 login() 获取新 Token
```

**Token 续期最佳实践**:
- Nacos Server 端配置 `nacos.core.auth.token.expire.seconds=18000`(5 小时)
- Token 在 TTL 的 80% 处(4 小时)主动续期,避免请求中过期导致 403
- SDK 的 `login()` 在上一次 login 成功后的 TTL-60s 时预刷新

## 六、命名空间级别的权限控制

Nacos 支持按 namespace 配置白名单:

```
namespace sca-lab-dev:
  user-A: 读写
  user-B: 只读
  user-C: 无权限(看不到这个 namespace)
```

配置方式: Nacos 控制台 -> 权限控制 -> 角色管理 -> 绑定 namespace + 用户

## 七、当前环境限制

我们用的是 Nacos standalone + `NACOS_AUTH_ENABLE=false`(鉴权完全关闭)。B5 为概念演示,无法端到端验证。生产环境替换步骤:

1. 实现 `ClientAuthService` 接口(客户端侧)
2. 注册 SPI: `META-INF/services/com.alibaba.nacos.plugin.auth.spi.client.ClientAuthService`
3. 设置 `nacos.core.auth.enabled=true`(服务端)
4. 重启 Nacos Server + 客户端

## 八、反模式

| 反模式 | 说明 |
|--------|------|
| 在 metadata 中传密码 | Nacos metadata 是明文的,所有订阅者都能看到,不能用 metadata 传认证凭据 |
| login() 中做阻塞网络 IO | login 在应用启动时调用,超时会导致启动失败。设在 3s 超时 + 重试 |
| Token 永不过期 | 设置过长的 TTL 等于没设。推荐 5h,长任务(Jet Job)用单独的 service account |
