package com.scalab.sentinel.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * B-04: 自定义 BlockExceptionHandler——统一限流/熔断响应格式。
 *
 * <p>Sentinel 默认的 WebMVC 适配器在拦截时返回 HTTP 429 + 空白 body。
 * 本 Handler 将所有 BlockException 类型映射为友好的 JSON 响应，
 * 包含限流类型、资源名、规则描述。
 */
@Configuration
public class SentinelBlockHandlerConfig {

    @Bean
    public BlockExceptionHandler customBlockExceptionHandler() {
        return new BlockExceptionHandler() {
            @Override
            public void handle(HttpServletRequest request, HttpServletResponse response,
                               String resourceName, BlockException ex) throws Exception {
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");

                Map<String, Object> body = new HashMap<>();
                body.put("blocked", true);
                body.put("resource", resourceName);
                body.put("type", classify(ex));
                body.put("rule", ex.getRule() != null ? ex.getRule().getResource() : "N/A");
                body.put("message", ex.getMessage());

                PrintWriter out = response.getWriter();
                out.print(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body));
                out.flush();
            }

            private String classify(BlockException ex) {
                if (ex instanceof FlowException) return "FLOW";
                if (ex instanceof ParamFlowException) return "PARAM_FLOW";
                if (ex instanceof DegradeException) return "DEGRADE";
                if (ex instanceof AuthorityException) return "AUTHORITY";
                if (ex instanceof SystemBlockException) return "SYSTEM";
                return "UNKNOWN";
            }
        };
    }
}
