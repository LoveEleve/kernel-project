package com.myxhs.demo.tomcat.config;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.myxhs.demo.tomcat.web.RequestTimingFilter;

/**
 * 示例：用 {@link FilterRegistrationBean} 注册自定义 Filter（推荐方式）。
 *
 * <p>四种注册方式对比（详见 tomcat-lab/README.md）：
 * <ol>
 *   <li>Filter 作为 Spring Bean（如加 {@code @Component}）→ Boot 自动注册到 {@code /*}；顺序用 {@code @Order}</li>
 *   <li>{@link FilterRegistrationBean}（本类）→ 精确控制 URL、顺序、dispatcher types、名称</li>
 *   <li>{@code @WebFilter} + {@code @ServletComponentScan} → Servlet 原生注解方式</li>
 *   <li>直接实现 {@code jakarta.servlet.Filter}（不继承 OncePerRequestFilter）→ 注意 dispatch 重复执行问题</li>
 * </ol>
 *
 * <p>常见坑：Filter 既是 Spring Bean（自动注册）又放入 FilterRegistrationBean → <b>注册两次</b>。
 */
@Configuration(proxyBeanMethods = false)
public class FilterConfig {

    @Bean
    FilterRegistrationBean<RequestTimingFilter> requestTimingFilter() {
        FilterRegistrationBean<RequestTimingFilter> registration =
                new FilterRegistrationBean<>(new RequestTimingFilter());
        registration.setName("labRequestTimingFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);          // 越小越先执行
        registration.setDispatcherTypes(DispatcherType.REQUEST);    // 默认仅 REQUEST；可加 ASYNC/ERROR/FORWARD/INCLUDE
        return registration;
    }
}
