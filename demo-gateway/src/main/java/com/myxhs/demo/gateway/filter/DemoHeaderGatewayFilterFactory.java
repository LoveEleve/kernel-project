package com.myxhs.demo.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * L14 ★分水岭：自定义 {@code GatewayFilterFactory}（能配到 yml 的过滤器）。
 *
 * <p>用途：给下游请求加一个头。yml 里写（短写语法）：
 * <pre>
 *   filters:
 *     - DemoHeader=X-Demo-Factory, hello-from-my-factory
 * </pre>
 * 其中 {@code DemoHeader} = 类名去掉 {@code GatewayFilterFactory} 后缀（NameUtils 规则）。
 *
 * <p>三段式：Factory（本类）→ Config（收参数）→ GatewayFilter（干活的 lambda）。
 */
@Component
public class DemoHeaderGatewayFilterFactory
        extends AbstractGatewayFilterFactory<DemoHeaderGatewayFilterFactory.Config> {

    public DemoHeaderGatewayFilterFactory() {
        super(Config.class);            // ★ 必须：把 Config 类型告诉基类
    }

    /**
     * ★ 短写语法靠它：DemoHeader=A, B → name=A, value=B。
     * <p>不重写的话：短写绑不上字段（Config 里为空），只有完整 Map 写法能用。
     *
     * yml 中里有两种写法： 短写 / 长写
     *  - 短写 :  - DemoHeader=X-Demo-Factory, hello-from-my-factory
     *  - 长写 :
     *      - name: DemoHeader
     *          args:
     *              name: X-Demo-Factory
     *              value: hello-from-my-factory
     */
    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("name", "value");
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            // pre 阶段：改请求头（mutate 出【新对象】，然后用新的往下传）
            ServerHttpRequest newServerHttpRequest = exchange.getRequest().mutate()
                    .header(config.getName(), config.getValue())
                    .build();
            return chain.filter(exchange.mutate().request(newServerHttpRequest).build());
        };
    }

    /** 配置类：字段名必须与 shortcutFieldOrder() 里的一致 */
    /**
     *  配置类：就是用来装 yml 里那行参数的普通 Java 类
     *  - DemoHeader=X-Demo-Factory, hello-from-my-factory
     * */
    public static class Config {

        private String name;

        private String value;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
