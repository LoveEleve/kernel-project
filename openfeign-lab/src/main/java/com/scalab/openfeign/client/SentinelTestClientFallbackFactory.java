package com.scalab.openfeign.client;

import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class SentinelTestClientFallbackFactory implements FallbackFactory<SentinelTestClient> {

    @Override
    public SentinelTestClient create(Throwable cause) {
        return () -> {
            Map<String, Object> result = new HashMap<>();
            result.put("fallback", true);
            result.put("causeType", cause.getClass().getSimpleName());
            result.put("causeMessage", cause.getMessage());
            return result;
        };
    }
}
