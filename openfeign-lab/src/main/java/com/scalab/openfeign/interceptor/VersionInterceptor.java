package com.scalab.openfeign.interceptor;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.stereotype.Component;

@Component
public class VersionInterceptor implements RequestInterceptor {

    public static final String VERSION_HEADER = "X-API-Version";

    private static final ThreadLocal<String> versionHolder = new ThreadLocal<>();

    public static void setVersion(String version) {
        versionHolder.set(version);
    }

    public static void clear() {
        versionHolder.remove();
    }

    @Override
    public void apply(RequestTemplate template) {
        String version = versionHolder.get();
        if (version != null && !version.isEmpty()) {
            template.header(VERSION_HEADER, version);
        }
    }
}
