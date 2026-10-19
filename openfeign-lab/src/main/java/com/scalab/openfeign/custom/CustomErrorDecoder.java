package com.scalab.openfeign.custom;

import feign.Response;
import feign.RetryableException;
import feign.Util;
import feign.codec.ErrorDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

public class CustomErrorDecoder implements ErrorDecoder {

    private static final Logger log = LoggerFactory.getLogger(CustomErrorDecoder.class);

    private final ErrorDecoder delegate;

    public CustomErrorDecoder(ErrorDecoder delegate) {
        this.delegate = delegate;
    }

    @Override
    public Exception decode(String methodKey, Response response) {
        byte[] bodyBytes = null;
        if (response.body() != null) {
            try {
                bodyBytes = Util.toByteArray(response.body().asInputStream());
            } catch (IOException ignored) {
            }
        }

        Response repeatable = response.toBuilder().body(bodyBytes).build();
        String bodyText = bodyBytes != null ? new String(bodyBytes, StandardCharsets.UTF_8) : "";

        log.debug("[CustomErrorDecoder] {} -> status={}, body={}", methodKey, response.status(),
                bodyText.length() > 200 ? bodyText.substring(0, 200) + "..." : bodyText);

        int status = response.status();

        if (status == 404) {
            return new ResourceNotFoundException(methodKey, status, bodyText);
        }

        if (status == 429 || status >= 500) {
            String retryAfter = firstOrNull(response.headers(), Util.RETRY_AFTER);
            if (retryAfter != null) {
                return delegate.decode(methodKey, repeatable);
            }
            if (status == 429) {
                return new RateLimitExceededException(methodKey, status, bodyText);
            }
            return new ServerErrorException(methodKey, status, bodyText);
        }

        return delegate.decode(methodKey, repeatable);
    }

    private static String firstOrNull(Map<String, Collection<String>> map, String key) {
        if (map.containsKey(key) && !map.get(key).isEmpty()) {
            return map.get(key).iterator().next();
        }
        return null;
    }

    public static class ResourceNotFoundException extends RuntimeException {
        private final String methodKey;
        private final int status;
        private final String body;

        public ResourceNotFoundException(String methodKey, int status, String body) {
            super("Resource not found: " + methodKey + " (status=" + status + ")");
            this.methodKey = methodKey;
            this.status = status;
            this.body = body;
        }

        public String getMethodKey() { return methodKey; }
        public int getStatus() { return status; }
        public String getBody() { return body; }
    }

    public static class RateLimitExceededException extends RuntimeException {
        private final String methodKey;
        private final int status;
        private final String body;

        public RateLimitExceededException(String methodKey, int status, String body) {
            super("Rate limit exceeded: " + methodKey + " (status=" + status + ")");
            this.methodKey = methodKey;
            this.status = status;
            this.body = body;
        }

        public String getMethodKey() { return methodKey; }
        public int getStatus() { return status; }
        public String getBody() { return body; }
    }

    public static class ServerErrorException extends RuntimeException {
        private final String methodKey;
        private final int status;
        private final String body;

        public ServerErrorException(String methodKey, int status, String body) {
            super("Server error: " + methodKey + " (status=" + status + ")");
            this.methodKey = methodKey;
            this.status = status;
            this.body = body;
        }

        public String getMethodKey() { return methodKey; }
        public int getStatus() { return status; }
        public String getBody() { return body; }
    }
}
