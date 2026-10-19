package com.myxhs.demo.provider;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * L38 实验端点。
 *
 * <p>- {@code GET /cache/stamp}：每次调用序号 +1（用来观察缓存是否命中）；可用 {@code ?cc=} 控制响应的
 *    Cache-Control、{@code ?vary=} 控制 Vary、{@code ?pad=N} 往 body 里塞 N 个字符（用来测缓存大小上限）。
 * <p>- {@code POST /body/echo}：回显收到的 JSON body（用来观察 ModifyRequestBody 的改写效果）。
 */
@RestController
public class CacheLabController {

    private final AtomicLong stampSeq = new AtomicLong();

    @Value("${server.port}")
    private int port;

    @GetMapping("/cache/stamp")
    public ResponseEntity<Map<String, Object>> stamp(
            @RequestParam(name = "cc", defaultValue = "") String cacheControl,
            @RequestParam(name = "vary", defaultValue = "") String vary,
            @RequestParam(name = "pad", defaultValue = "0") int pad) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seq", stampSeq.incrementAndGet());
        body.put("port", port);
        body.put("time", Instant.now().toString());
        if (pad > 0) {
            body.put("pad", "x".repeat(pad));
        }
        HttpHeaders headers = new HttpHeaders();
        if (!cacheControl.isBlank()) {
            headers.set(HttpHeaders.CACHE_CONTROL, cacheControl);
        }
        if (!vary.isBlank()) {
            headers.set(HttpHeaders.VARY, vary);
        }
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @PostMapping("/body/echo")
    public Map<String, Object> echoBody(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("port", port);
        result.put("contentType", request.getContentType());
        result.put("received", body);
        return result;
    }

}
