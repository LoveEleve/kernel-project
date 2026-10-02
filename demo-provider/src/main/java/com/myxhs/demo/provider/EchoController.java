package com.myxhs.demo.provider;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 回显控制器：把收到的请求信息原样返回，用来观察网关到底做了什么
 * （路径被改写了吗？加了哪些 Header？TraceId 传下来了吗？）
 */
@RestController
public class EchoController {

    private static final Logger log = LoggerFactory.getLogger(EchoController.class);

    @Value("${server.port}")
    private int port;

    @GetMapping("/echo/{msg}")
    public Map<String, Object> echo(@PathVariable String msg, HttpServletRequest request) {
        log.info("[provider] /echo/{} 收到请求, traceId={}", msg, request.getHeader("X-Demo-Trace-Id"));
        Map<String, Object> body = base(request);
        body.put("msg", msg);
        return body;
    }

    @GetMapping("/hello")
    public Map<String, Object> hello(@RequestParam(defaultValue = "world") String name,
                                     HttpServletRequest request) {
        Map<String, Object> body = base(request);
        body.put("greeting", "hello " + name);
        return body;
    }

    /** 慢接口：用来观察网关的 response-timeout / 重试 / 熔断行为 */
    @GetMapping("/slow")
    public Map<String, Object> slow(@RequestParam(defaultValue = "3000") long ms) throws InterruptedException {
        Thread.sleep(ms);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sleptMs", ms);
        body.put("port", port);
        return body;
    }

    private Map<String, Object> base(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("port", port);
        body.put("method", request.getMethod());
        body.put("uri", request.getRequestURI());
        body.put("query", request.getQueryString());
        body.put("headers", headers(request));
        return body;
    }

    private Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }
}
