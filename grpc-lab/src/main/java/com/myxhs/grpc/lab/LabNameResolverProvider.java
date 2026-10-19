package com.myxhs.grpc.lab;

import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 第 08 篇：自定义 NameResolver（scheme = lab）。
 * <p>
 * 把 {@code lab:///127.0.0.1:50051,127.0.0.1:50052} 解析成固定的地址列表——
 * 演示 NameResolver 的职责：目标字符串 → EquivalentAddressGroup 列表。
 * </p>
 */
public class LabNameResolverProvider extends NameResolverProvider {

    public static final String SCHEME = "lab";

    @Override
    protected boolean isAvailable() {
        return true;
    }

    @Override
    protected int priority() {
        return 5;
    }

    @Override
    public String getDefaultScheme() {
        return SCHEME;
    }

    @Override
    public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        if (!SCHEME.equals(targetUri.getScheme())) {
            return null;
        }
        List<EquivalentAddressGroup> addresses = new ArrayList<>();
        String path = targetUri.getPath(); // "/127.0.0.1:50051,127.0.0.1:50052"
        for (String hostPort : path.substring(1).split(",")) {
            String[] parts = hostPort.split(":");
            addresses.add(new EquivalentAddressGroup(
                    new InetSocketAddress(parts[0], Integer.parseInt(parts[1]))));
        }
        return new NameResolver() {
            @Override
            public String getServiceAuthority() {
                return "lab";
            }

            @Override
            public void start(Listener2 listener) {
                listener.onResult(ResolutionResult.newBuilder().setAddresses(addresses).build());
            }

            @Override
            public void shutdown() {
            }
        };
    }
}
