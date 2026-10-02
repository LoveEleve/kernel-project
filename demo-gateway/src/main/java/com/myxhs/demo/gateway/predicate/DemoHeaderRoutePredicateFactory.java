package com.myxhs.demo.gateway.predicate;

import org.springframework.cloud.gateway.handler.predicate.AbstractRoutePredicateFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.function.Predicate;

/**
 * L15：自定义 {@code RoutePredicateFactory}（自己造一个"匹配器"）。
 *
 * <p>用途：只有请求头等于指定值时，这条路由才匹配。yml 里写（短写语法）：
 * <pre>
 *   predicates:
 *     - Path=/predicate-lab/**
 *     - DemoHeader=X-Demo-Env, gray
 * </pre>
 * 其中 {@code DemoHeader} = 类名去掉 {@code RoutePredicateFactory} 后缀（NameUtils 规则）。
 *
 * <p>与 L14 的 Filter 工厂对照：结构一样（三段式），差别只有
 * ① 基类 {@code AbstractRoutePredicateFactory} ② {@code apply()} 返回 {@code Predicate}。
 *
 * <p>⚠️ 纪律：谓词在【匹配阶段】执行（EventLoop 线程），每条路由的谓词每个请求都要跑 ——
 * 这里**不能**做阻塞或远程调用。
 */
@Component
public class DemoHeaderRoutePredicateFactory
        extends AbstractRoutePredicateFactory<DemoHeaderRoutePredicateFactory.Config> {

    public DemoHeaderRoutePredicateFactory() {
        super(Config.class);
    }

    /** 短写语法：DemoHeader=X-Demo-Env, gray → headerName / headerValue */
    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("headerName", "headerValue");
    }

    @Override
    public Predicate<ServerWebExchange> apply(Config config) {
        return exchange -> {
            String actual = exchange.getRequest().getHeaders().getFirst(config.getHeaderName());
            return config.getHeaderValue().equals(actual);
        };
    }

    public static class Config {

        private String headerName;

        private String headerValue;

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(String headerName) {
            this.headerName = headerName;
        }

        public String getHeaderValue() {
            return headerValue;
        }

        public void setHeaderValue(String headerValue) {
            this.headerValue = headerValue;
        }
    }
}
