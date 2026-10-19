package com.myxhs.grpc.lab;

import com.google.common.util.concurrent.ListenableFuture;
import com.myxhs.grpc.lab.proto.GreeterGrpc;
import com.myxhs.grpc.lab.proto.HelloReply;
import com.myxhs.grpc.lab.proto.HelloRequest;
import com.myxhs.grpc.lab.proto.StreamerGrpc;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.NameResolverRegistry;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.reflection.v1.ServerReflectionGrpc;
import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.ConnectivityState;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * gRPC Client 入口（Netty 传输）：三种 Stub 对照 + 重试/对冲实验模式。
 * <p>
 * 目标：-Dlab.target=localhost:50051
 * 第 02 篇实验：
 *   -Dlab.retry=true            启用重试（service config: maxAttempts=3, UNAVAILABLE 可重试）
 *   -Dlab.hedging=true          启用对冲（hedgingDelay=0.1s, maxAttempts=2）
 *   -Dlab.debug.callclass=true  拦截器打印 ClientCall 实际实现类名
 * </p>
 */
public class ClientApp {

    /** 第 06 篇：Context 探针用的 key（与服务端同名） */
    static final Context.Key<String> CTX_KEY = Context.key("lab-ctx");

    /** 第 06 篇：Context 探针（客户端）——interceptCall 与回调线程能否看到 Context 值 */
    static class ContextProbeClientInterceptor implements ClientInterceptor {
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            System.out.println("[lab-client] interceptor interceptCall Context = " + CTX_KEY.get());
            ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);
            return new ForwardingClientCall.SimpleForwardingClientCall<>(call) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {
                        @Override
                        public void onClose(Status status, Metadata trailers) {
                            System.out.println("[lab-client] interceptor onClose Context = " + CTX_KEY.get());
                            super.onClose(status, trailers);
                        }
                    }, headers);
                }
            };
        }
    }

    /** 第 06 篇：客户端 Context 跨线程演示（主线程 / 裸线程 / 包装线程） */
    static void runContextDemo(ManagedChannel channel, HelloRequest request) throws Exception {
        Context ctx = Context.current().withValue(CTX_KEY, "ctx-123");
        Context previous = ctx.attach();
        try {
            System.out.println("[lab-client] 主线程 Context = " + CTX_KEY.get());
            Thread raw = new Thread(() ->
                    System.out.println("[lab-client] 裸线程 Context = " + CTX_KEY.get()));
            raw.start();
            raw.join();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Context.currentContextExecutor(executor).execute(() ->
                    System.out.println("[lab-client] 包装线程 Context = " + CTX_KEY.get()));
            executor.shutdown();
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            HelloReply reply = GreeterGrpc.newBlockingStub(channel).sayHello(request);
            System.out.println("[lab-client] 调用完成 = " + reply.getMessage());
        } finally {
            ctx.detach(previous);
        }
    }

    /** 第 04 篇：顺序探针（客户端）——观察 interceptCall 与 onClose 的顺序 */
    static class OrderClientInterceptor implements ClientInterceptor {
        private final String name;

        OrderClientInterceptor(String name) {
            this.name = name;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            System.out.println("[lab-client] interceptor " + name + " interceptCall");
            ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);
            return new ForwardingClientCall.SimpleForwardingClientCall<>(call) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {
                        @Override
                        public void onClose(Status status, Metadata trailers) {
                            System.out.println("[lab-client] interceptor " + name + " onClose");
                            super.onClose(status, trailers);
                        }
                    }, headers);
                }
            };
        }
    }

    /** 第 04 篇：Metadata 探针（客户端）——发请求附件、读响应 header/trailer */
    static class MetadataClientInterceptor implements ClientInterceptor {
        static final Metadata.Key<String> TOKEN =
                Metadata.Key.of("x-lab-token", Metadata.ASCII_STRING_MARSHALLER);
        static final Metadata.Key<byte[]> BIN =
                Metadata.Key.of("x-lab-bin-bin", Metadata.BINARY_BYTE_MARSHALLER);

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            Metadata extra = new Metadata();
            extra.put(TOKEN, "lab-token-123");
            extra.put(BIN, new byte[]{1, 2, 3});
            ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);
            return new ForwardingClientCall.SimpleForwardingClientCall<>(call) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    headers.merge(extra);
                    super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {
                        @Override
                        public void onHeaders(Metadata headers) {
                            System.out.println("[lab-client] response headers = " + headers);
                            super.onHeaders(headers);
                        }

                        @Override
                        public void onClose(Status status, Metadata trailers) {
                            System.out.println("[lab-client] response trailers = " + trailers);
                            super.onClose(status, trailers);
                        }
                    }, headers);
                }
            };
        }
    }

    /** 第 02 篇：打印 channel.newCall 返回的 ClientCall 实现类（ClientCallImpl / RetriableStream） */
    static class DebugCallClassInterceptor implements ClientInterceptor {
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);
            System.out.println("[lab-client] ClientCall class = " + call.getClass().getName());
            return call;
        }
    }

    /** 第 02 篇：重试/对冲的 service config（等价于 gRPC 标准 JSON 结构，直接构造 Map） */
    static Map<String, Object> serviceConfig(boolean retry) {
        Map<String, Object> name = Map.of("service", "myxhs.lab.Greeter");
        if (retry) {
            Map<String, Object> policy = Map.of(
                    "maxAttempts", 3.0,
                    "initialBackoff", "0.1s",
                    "maxBackoff", "1s",
                    "backoffMultiplier", 2.0,
                    "retryableStatusCodes", List.of("UNAVAILABLE"));
            return Map.of("methodConfig", List.of(Map.of("name", List.of(name), "retryPolicy", policy)));
        }
        Map<String, Object> policy = Map.of(
                "maxAttempts", 2.0,
                "hedgingDelay", "0.1s",
                "nonFatalStatusCodes", List.of("UNAVAILABLE"));
        return Map.of("methodConfig", List.of(Map.of("name", List.of(name), "hedgingPolicy", policy)));
    }

    /** 第 05 篇：四种流式模式 + 流控对照 */
    static void runStreamMode(String mode, ManagedChannel channel, HelloRequest request) throws Exception {
        long t0 = System.currentTimeMillis();
        CountDownLatch latch = new CountDownLatch(1);
        StreamerGrpc.StreamerStub stub = StreamerGrpc.newStub(channel);

        if ("server".equals(mode)) {
            // 1 请求 → N 响应（默认自动流控）
            stub.serverStream(request, new StreamObserver<HelloReply>() {
                @Override
                public void onNext(HelloReply value) {
                    System.out.println("[lab-client] serverStream 收到 " + value.getMessage()
                            + " @" + (System.currentTimeMillis() - t0) + "ms");
                }

                @Override
                public void onError(Throwable t) {
                    System.out.println("[lab-client] serverStream FAIL: " + t.getMessage());
                    latch.countDown();
                }

                @Override
                public void onCompleted() {
                    latch.countDown();
                }
            });
        } else if ("flow".equals(mode)) {
            // 1 请求 → N 响应（手动流控：request(1) + 每收一条等 400ms）
            stub.serverStream(request, new ClientResponseObserver<HelloRequest, HelloReply>() {
                private ClientCallStreamObserver<HelloRequest> requestStream;

                @Override
                public void beforeStart(ClientCallStreamObserver<HelloRequest> requestStream) {
                    this.requestStream = requestStream;
                    requestStream.disableAutoInboundFlowControl();
                    requestStream.request(1);
                }

                @Override
                public void onNext(HelloReply value) {
                    System.out.println("[lab-client] flow 收到 " + value.getMessage()
                            + " @" + (System.currentTimeMillis() - t0) + "ms");
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    requestStream.request(1);
                }

                @Override
                public void onError(Throwable t) {
                    System.out.println("[lab-client] flow FAIL: " + t.getMessage());
                    latch.countDown();
                }

                @Override
                public void onCompleted() {
                    latch.countDown();
                }
            });
        } else if ("client".equals(mode)) {
            // N 请求 → 1 响应
            StreamObserver<HelloRequest> req = stub.clientStream(new StreamObserver<HelloReply>() {
                @Override
                public void onNext(HelloReply value) {
                    System.out.println("[lab-client] clientStream 结果 = " + value.getMessage()
                            + " @" + (System.currentTimeMillis() - t0) + "ms");
                }

                @Override
                public void onError(Throwable t) {
                    System.out.println("[lab-client] clientStream FAIL: " + t.getMessage());
                    latch.countDown();
                }

                @Override
                public void onCompleted() {
                    latch.countDown();
                }
            });
            for (int i = 1; i <= 3; i++) {
                req.onNext(HelloRequest.newBuilder().setName("c" + i).build());
            }
            req.onCompleted();
        } else if ("bidi".equals(mode)) {
            // N ↔ N
            StreamObserver<HelloRequest> req = stub.bidiStream(new StreamObserver<HelloReply>() {
                @Override
                public void onNext(HelloReply value) {
                    System.out.println("[lab-client] bidi 收到 " + value.getMessage()
                            + " @" + (System.currentTimeMillis() - t0) + "ms");
                }

                @Override
                public void onError(Throwable t) {
                    System.out.println("[lab-client] bidi FAIL: " + t.getMessage());
                    latch.countDown();
                }

                @Override
                public void onCompleted() {
                    latch.countDown();
                }
            });
            for (int i = 1; i <= 3; i++) {
                req.onNext(HelloRequest.newBuilder().setName("b" + i).build());
            }
            req.onCompleted();
        } else {
            System.out.println("[lab-client] 未知 lab.stream 模式: " + mode);
            return;
        }
        if (!latch.await(15, TimeUnit.SECONDS)) {
            System.out.println("[lab-client] " + mode + " 超时未完成");
        }
    }

    public static void main(String[] args) throws Exception {
        String target = System.getProperty("lab.target", "localhost:50051");
        boolean retry = Boolean.getBoolean("lab.retry");
        boolean hedging = Boolean.getBoolean("lab.hedging");
        boolean debugCallClass = Boolean.getBoolean("lab.debug.callclass");

        if (LabNameResolverProvider.SCHEME.equals(System.getProperty("lab.resolver", ""))) {
            NameResolverRegistry.getDefaultRegistry().register(new LabNameResolverProvider());
        }
        NettyChannelBuilder builder = NettyChannelBuilder.forTarget(target).usePlaintext();
        String lbPolicy = System.getProperty("lab.lb", "");
        if (!lbPolicy.isEmpty()) {
            builder.defaultLoadBalancingPolicy(lbPolicy);
        }
        if (retry || hedging) {
            // 1.83.1 的 retryEnabled 默认就是 true（ManagedChannelImplBuilder:195）；
            // 这里用 -Dlab.enable.retry=false 跳过显式调用，验证"默认开关"行为
            boolean callEnableRetry = Boolean.parseBoolean(System.getProperty("lab.enable.retry", "true"));
            if (callEnableRetry) {
                builder.enableRetry();
            }
            builder.defaultServiceConfig(serviceConfig(retry));
            System.out.println("[lab-client] enableRetry() called = " + callEnableRetry);
        }
        if (debugCallClass) {
            builder.intercept(new DebugCallClassInterceptor());
        }
        if (Boolean.getBoolean("lab.interceptors")) {
            builder.intercept(new OrderClientInterceptor("A"));
            builder.intercept(new OrderClientInterceptor("B"));
        }
        if (Boolean.getBoolean("lab.meta")) {
            builder.intercept(new MetadataClientInterceptor());
        }
        if (Boolean.getBoolean("lab.context")) {
            builder.intercept(new ContextProbeClientInterceptor());
        }
        ManagedChannel channel = builder.build();

        HelloRequest request = HelloRequest.newBuilder().setName("lab").build();

        // 第 02 篇模式：单次调用观察重试/对冲行为
        if (retry || hedging) {
            String mode = retry ? "retry" : "hedging";
            // 预热连接：把建连成本从实验耗时里剔除（顺带观察 channel 状态机）
            long warmupDeadline = System.currentTimeMillis() + 3000;
            while (channel.getState(true) != ConnectivityState.READY
                    && System.currentTimeMillis() < warmupDeadline) {
                Thread.sleep(50);
            }
            System.out.println("[lab-client] channel state = " + channel.getState(false));
            long t0 = System.currentTimeMillis();
            try {
                HelloReply reply = GreeterGrpc.newBlockingStub(channel).sayHello(request);
                System.out.println("[lab-client] " + mode + " = " + reply.getMessage()
                        + " in " + (System.currentTimeMillis() - t0) + "ms");
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage()).replace('\n', ' ');
                if (msg.length() > 140) {
                    msg = msg.substring(0, 140);
                }
                System.out.println("[lab-client] " + mode + " -> FAIL in " + (System.currentTimeMillis() - t0)
                        + "ms: " + e.getClass().getSimpleName() + " | " + msg);
            }
            channel.shutdownNow();
            System.out.println("[lab-client] done");
            System.exit(0);
        }

        // 第 08 篇模式：多调用（NameResolver/LB 实验：打印每个响应的实例标识）
        int discoveryCalls = Integer.getInteger("lab.discovery", 0);
        if (discoveryCalls > 0) {
            // 预热：等所有子通道 READY（否则 round_robin 只有部分后端可用）
            channel.getState(true);
            Thread.sleep(800);
            GreeterGrpc.GreeterBlockingStub stub = GreeterGrpc.newBlockingStub(channel);
            for (int i = 1; i <= discoveryCalls; i++) {
                try {
                    System.out.println("[lab-client] call#" + i + " -> OK: "
                            + stub.sayHello(request).getMessage());
                } catch (Exception e) {
                    System.out.println("[lab-client] call#" + i + " -> FAIL: "
                            + e.getClass().getSimpleName() + " | " + e.getMessage());
                }
            }
            channel.shutdownNow();
            System.out.println("[lab-client] done");
            System.exit(0);
        }

        // 第 08 篇模式：健康检查
        if (Boolean.getBoolean("lab.health")) {
            HealthGrpc.HealthBlockingStub health = HealthGrpc.newBlockingStub(channel);
            HealthCheckRequest req = HealthCheckRequest.newBuilder()
                    .setService("myxhs.lab.Greeter").build();
            System.out.println("[lab-client] health = " + health.check(req).getStatus());
            if (Boolean.getBoolean("lab.health.wait")) {
                Thread.sleep(4000);
                System.out.println("[lab-client] health(4s 后) = " + health.check(req).getStatus());
            }
            channel.shutdownNow();
            System.out.println("[lab-client] done");
            System.exit(0);
        }

        // 第 08 篇模式：Reflection 列出服务（无需任何 Stub）
        if (Boolean.getBoolean("lab.reflection")) {
            ServerReflectionGrpc.ServerReflectionStub refl = ServerReflectionGrpc.newStub(channel);
            CountDownLatch latch = new CountDownLatch(1);
            StreamObserver<ServerReflectionRequest> reqStream = refl.serverReflectionInfo(
                    new StreamObserver<ServerReflectionResponse>() {
                        @Override
                        public void onNext(ServerReflectionResponse resp) {
                            resp.getListServicesResponse().getServiceList().forEach(s ->
                                    System.out.println("[lab-client] reflection service = " + s.getName()));
                        }

                        @Override
                        public void onError(Throwable t) {
                            System.out.println("[lab-client] reflection FAIL: " + t.getMessage());
                            latch.countDown();
                        }

                        @Override
                        public void onCompleted() {
                            latch.countDown();
                        }
                    });
            reqStream.onNext(ServerReflectionRequest.newBuilder().setListServices("").build());
            reqStream.onCompleted();
            if (!latch.await(5, TimeUnit.SECONDS)) {
                System.out.println("[lab-client] reflection 超时");
            }
            channel.shutdownNow();
            System.out.println("[lab-client] done");
            System.exit(0);
        }

        // 第 06 篇模式：Context 跨线程演示（内部会发起一次调用）
        if (Boolean.getBoolean("lab.context")) {
            runContextDemo(channel, request);
            channel.shutdownNow();
            System.out.println("[lab-client] done");
            System.exit(0);
        }

        // 第 05 篇模式：四种流式调用
        String streamMode = System.getProperty("lab.stream", "");
        if (!streamMode.isEmpty()) {
            runStreamMode(streamMode, channel, request);
            channel.shutdownNow();
            System.out.println("[lab-client] done");
            System.exit(0);
        }

        // 第 03 篇模式：调用第二个服务（多 ServiceDefinition）
        if (Boolean.getBoolean("lab.echo")) {
            HelloReply echoReply = com.myxhs.grpc.lab.proto.EchoGrpc.newBlockingStub(channel).echo(request);
            System.out.println("[lab-client] echo = " + echoReply.getMessage());
            channel.shutdownNow();
            System.out.println("[lab-client] done");
            System.exit(0);
        }

        // 第 03 篇模式：单次调用（优雅关闭实验用，减少输出噪音）；第 06 篇支持 deadline
        if (Boolean.getBoolean("lab.single")) {
            int deadlineMs = Integer.getInteger("lab.deadline.ms", 0);
            long t0 = System.currentTimeMillis();
            try {
                GreeterGrpc.GreeterBlockingStub stub = GreeterGrpc.newBlockingStub(channel);
                if (deadlineMs > 0) {
                    stub = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
                }
                HelloReply reply = stub.sayHello(request);
                System.out.println("[lab-client] single = " + reply.getMessage()
                        + " in " + (System.currentTimeMillis() - t0) + "ms");
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage()).replace('\n', ' ');
                if (msg.length() > 140) {
                    msg = msg.substring(0, 140);
                }
                System.out.println("[lab-client] single -> FAIL in " + (System.currentTimeMillis() - t0)
                        + "ms: " + e.getClass().getSimpleName() + " | " + msg);
                if (Boolean.getBoolean("lab.meta") || deadlineMs > 0) {
                    Status st = Status.fromThrowable(e);
                    Metadata tr = Status.trailersFromThrowable(e);
                    System.out.println("[lab-client] status = " + st.getCode() + " | " + st.getDescription());
                    if (tr != null) {
                        System.out.println("[lab-client] error trailers = " + tr);
                    }
                }
            }
            channel.shutdownNow();
            System.out.println("[lab-client] done");
            System.exit(0);
        }

        // ① BlockingStub：看起来"同步"，内部仍是异步 + 阻塞等待
        GreeterGrpc.GreeterBlockingStub blockingStub = GreeterGrpc.newBlockingStub(channel);
        long t0 = System.currentTimeMillis();
        HelloReply blockingReply = blockingStub.sayHello(request);
        System.out.println("[lab-client] blocking = " + blockingReply.getMessage()
                + " in " + (System.currentTimeMillis() - t0) + "ms");

        // ② FutureStub：返回 ListenableFuture，不阻塞发起线程
        GreeterGrpc.GreeterFutureStub futureStub = GreeterGrpc.newFutureStub(channel);
        long t1 = System.currentTimeMillis();
        ListenableFuture<HelloReply> future = futureStub.sayHello(request);
        HelloReply futureReply = future.get(5, TimeUnit.SECONDS);
        System.out.println("[lab-client] future   = " + futureReply.getMessage()
                + " in " + (System.currentTimeMillis() - t1) + "ms");

        // ③ AsyncStub：StreamObserver 回调，真正的异步形态
        GreeterGrpc.GreeterStub asyncStub = GreeterGrpc.newStub(channel);
        CountDownLatch latch = new CountDownLatch(1);
        long t2 = System.currentTimeMillis();
        asyncStub.sayHello(request, new StreamObserver<HelloReply>() {
            @Override
            public void onNext(HelloReply value) {
                System.out.println("[lab-client] async    = " + value.getMessage()
                        + " in " + (System.currentTimeMillis() - t2) + "ms");
            }

            @Override
            public void onError(Throwable t) {
                System.out.println("[lab-client] async FAIL: " + t.getClass().getSimpleName()
                        + " | " + t.getMessage());
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            System.out.println("[lab-client] async 超时未回调");
        }

        channel.shutdownNow();
        System.out.println("[lab-client] done");
        System.exit(0);
    }
}
