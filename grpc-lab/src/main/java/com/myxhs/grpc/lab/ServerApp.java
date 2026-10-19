package com.myxhs.grpc.lab;

import com.myxhs.grpc.lab.proto.EchoGrpc;
import com.myxhs.grpc.lab.proto.StreamerGrpc;
import com.myxhs.grpc.lab.proto.GreeterGrpc;
import com.myxhs.grpc.lab.proto.HelloReply;
import com.myxhs.grpc.lab.proto.HelloRequest;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.grpc.services.HealthStatusManager;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * gRPC Server 入口（Netty 传输）。
 * <p>
 * 端口：-Dlab.port=50051（默认 50051，gRPC 惯用端口）
 * 优雅关闭：-Dlab.graceful.ms=0（>0 时 SIGTERM 先走 graceful shutdown）
 * 故障注入（第 02 篇重试实验用）：
 *   -Dlab.fail.first=2     前 N 次调用返回错误（默认 0）
 *   -Dlab.fail.code=UNAVAILABLE  错误码（UNAVAILABLE/INTERNAL）
 *   -Dlab.server.slow.ms=800     每次调用前 sleep N ms（对冲实验用）
 * </p>
 */
public class ServerApp {

    /** 多实例实验用：-Dlab.provider.tag=A/B 让响应带上实例标识 */
    static final String PROVIDER_TAG = System.getProperty("lab.provider.tag", "");

    static String tag() {
        return PROVIDER_TAG.isEmpty() ? "" : "-" + PROVIDER_TAG;
    }

    /** 第 06 篇：Context 探针用的 key */
    static final Context.Key<String> CTX_KEY = Context.key("lab-ctx");

    /** 最小实现：unary SayHello + 故障注入（流式在后续实验加入） */
    static class GreeterImpl extends GreeterGrpc.GreeterImplBase {
        private final AtomicInteger calls = new AtomicInteger();
        private final int failFirst = Integer.getInteger("lab.fail.first", 0);
        private final int slowMs = Integer.getInteger("lab.server.slow.ms", 0);
        private final String failCode = System.getProperty("lab.fail.code", "UNAVAILABLE");

        @Override
        public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
            int n = calls.incrementAndGet();
            System.out.println("[lab-server] SayHello #" + n + " name=" + request.getName()
                    + ", 线程=" + Thread.currentThread().getName());
            if (slowMs > 0) {
                // 第 06 篇：分片 sleep + 检查 Context 取消（deadline 到期会取消服务端上下文）
                long end = System.currentTimeMillis() + slowMs;
                while (System.currentTimeMillis() < end) {
                    if (Context.current().isCancelled()) {
                        System.out.println("[lab-server] 检测到取消（deadline 到期），停止处理");
                        return;
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (Boolean.getBoolean("lab.context")) {
                System.out.println("[lab-server] handler Context = " + CTX_KEY.get());
                try {
                    Thread raw = new Thread(() ->
                            System.out.println("[lab-server] 裸线程 Context = " + CTX_KEY.get()));
                    raw.start();
                    raw.join();
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    Context.currentContextExecutor(executor).execute(() ->
                            System.out.println("[lab-server] 包装线程 Context = " + CTX_KEY.get()));
                    executor.shutdown();
                    executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (n <= failFirst) {
                Status status;
                try {
                    status = Status.fromCode(Status.Code.valueOf(failCode));
                } catch (IllegalArgumentException e) {
                    status = Status.UNAVAILABLE;
                }
                Metadata trailers = new Metadata();
                trailers.put(Metadata.Key.of("x-lab-error-trailer", Metadata.ASCII_STRING_MARSHALLER),
                        "injected-" + n);
                responseObserver.onError(status.withDescription("lab injected failure #" + n)
                        .asRuntimeException(trailers));
                return;
            }
            String message = "hello " + request.getName() + " from grpc-lab" + tag();
            responseObserver.onNext(HelloReply.newBuilder().setMessage(message).build());
            responseObserver.onCompleted();
        }
    }

    /** 第二个服务：证明 Server 是"多 ServiceDefinition 容器" */
    static class EchoImpl extends EchoGrpc.EchoImplBase {
        @Override
        public void echo(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
            System.out.println("[lab-server] Echo name=" + request.getName());
            responseObserver.onNext(HelloReply.newBuilder()
                    .setMessage("echo " + request.getName() + " from grpc-lab").build());
            responseObserver.onCompleted();
        }
    }

    /** 第 05 篇：四种流式模式的服务端实现 */
    static class StreamerImpl extends StreamerGrpc.StreamerImplBase {
        @Override
        public void serverStream(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
            ServerCallStreamObserver<HelloReply> obs = (ServerCallStreamObserver<HelloReply>) responseObserver;
            for (int i = 1; i <= 5; i++) {
                System.out.println("[lab-server] serverStream #" + i + " isReady=" + obs.isReady());
                obs.onNext(HelloReply.newBuilder().setMessage("stream-" + i).build());
            }
            obs.onCompleted();
        }

        @Override
        public StreamObserver<HelloRequest> clientStream(StreamObserver<HelloReply> responseObserver) {
            return new StreamObserver<HelloRequest>() {
                private int count = 0;

                @Override
                public void onNext(HelloRequest value) {
                    count++;
                    System.out.println("[lab-server] clientStream 收到 " + value.getName());
                }

                @Override
                public void onError(Throwable t) {
                    System.out.println("[lab-server] clientStream error: " + t.getMessage());
                }

                @Override
                public void onCompleted() {
                    responseObserver.onNext(HelloReply.newBuilder()
                            .setMessage("received " + count).build());
                    responseObserver.onCompleted();
                }
            };
        }

        @Override
        public StreamObserver<HelloRequest> bidiStream(StreamObserver<HelloReply> responseObserver) {
            return new StreamObserver<HelloRequest>() {
                @Override
                public void onNext(HelloRequest value) {
                    System.out.println("[lab-server] bidiStream 收到 " + value.getName());
                    responseObserver.onNext(HelloReply.newBuilder()
                            .setMessage("echo " + value.getName()).build());
                }

                @Override
                public void onError(Throwable t) {
                    System.out.println("[lab-server] bidiStream error: " + t.getMessage());
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            };
        }
    }

    /** 第 04 篇：顺序探针（服务端）——观察 interceptCall 与 onHalfClose 的顺序 */
    static class OrderServerInterceptor implements ServerInterceptor {
        private final String name;

        OrderServerInterceptor(String name) {
            this.name = name;
        }

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            System.out.println("[lab-server] interceptor " + name + " interceptCall");
            ServerCall.Listener<ReqT> delegate = next.startCall(call, headers);
            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
                @Override
                public void onHalfClose() {
                    System.out.println("[lab-server] interceptor " + name + " onHalfClose");
                    super.onHalfClose();
                }
            };
        }
    }

    /** 第 04 篇：Metadata 探针（服务端）——读请求附件、发响应 header/trailer */
    static class MetadataServerInterceptor implements ServerInterceptor {
        static final Metadata.Key<String> TOKEN =
                Metadata.Key.of("x-lab-token", Metadata.ASCII_STRING_MARSHALLER);
        static final Metadata.Key<byte[]> BIN =
                Metadata.Key.of("x-lab-bin-bin", Metadata.BINARY_BYTE_MARSHALLER);
        static final Metadata.Key<String> SERVER_HEADER =
                Metadata.Key.of("x-lab-server-header", Metadata.ASCII_STRING_MARSHALLER);
        static final Metadata.Key<String> SERVER_TRAILER =
                Metadata.Key.of("x-lab-server-trailer", Metadata.ASCII_STRING_MARSHALLER);

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            System.out.println("[lab-server] received metadata x-lab-token=" + headers.get(TOKEN)
                    + ", x-lab-bin-bin=" + java.util.Arrays.toString(headers.get(BIN)));
            // 正确姿势：包装 ServerCall 覆写 sendHeaders/close（不能直接调 sendHeaders，
            // 否则与 handler 首次 onNext 时的自动 sendHeaders 冲突：IllegalStateException）
            ServerCall<ReqT, RespT> wrapped = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                @Override
                public void sendHeaders(Metadata headers) {
                    headers.put(SERVER_HEADER, "from-server");
                    super.sendHeaders(headers);
                }

                @Override
                public void close(Status status, Metadata trailers) {
                    trailers.put(SERVER_TRAILER, "trailer-from-server");
                    super.close(status, trailers);
                }
            };
            return next.startCall(wrapped, headers);
        }
    }

    /** 第 06 篇：Context 探针（服务端）——用 Contexts.interceptCall 让 handler 看到上下文值 */
    static class ContextProbeServerInterceptor implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            Context ctx = Context.current().withValue(CTX_KEY, "server-ctx");
            System.out.println("[lab-server] interceptor Context.current = " + CTX_KEY.get());
            return Contexts.interceptCall(ctx, call, headers, next);
        }
    }

    /** 生命周期探针（第 03 篇）：打印 ServerCall.Listener 回调时序；拦截器机制第 04 篇讲 */
    static class LifecycleProbeInterceptor implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            String method = call.getMethodDescriptor().getFullMethodName();
            System.out.println("[lab-server] listener startCall " + method);
            ServerCall.Listener<ReqT> delegate = next.startCall(call, headers);
            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
                @Override
                public void onMessage(ReqT message) {
                    System.out.println("[lab-server] listener onMessage");
                    super.onMessage(message);
                }

                @Override
                public void onHalfClose() {
                    System.out.println("[lab-server] listener onHalfClose");
                    super.onHalfClose();
                }

                @Override
                public void onCancel() {
                    System.out.println("[lab-server] listener onCancel");
                    super.onCancel();
                }

                @Override
                public void onComplete() {
                    System.out.println("[lab-server] listener onComplete");
                    super.onComplete();
                }
            };
        }
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("lab.port", 50051);
        long gracefulMs = Long.getLong("lab.graceful.ms", 0L);
        boolean lifecycle = Boolean.getBoolean("lab.server.lifecycle");

        NettyServerBuilder builder = NettyServerBuilder.forPort(port)
                .addService(new GreeterImpl())
                .addService(new EchoImpl())
                .addService(new StreamerImpl());
        if (lifecycle) {
            builder.intercept(new LifecycleProbeInterceptor());
        }
        if (Boolean.getBoolean("lab.interceptors")) {
            builder.intercept(new OrderServerInterceptor("A"));
            builder.intercept(new OrderServerInterceptor("B"));
        }
        if (Boolean.getBoolean("lab.meta")) {
            builder.intercept(new MetadataServerInterceptor());
        }
        if (Boolean.getBoolean("lab.context")) {
            builder.intercept(new ContextProbeServerInterceptor());
        }
        if (Boolean.getBoolean("lab.health")) {
            HealthStatusManager healthManager = new HealthStatusManager();
            builder.addService(healthManager.getHealthService());
            healthManager.setStatus("", ServingStatus.SERVING);
            healthManager.setStatus("myxhs.lab.Greeter", ServingStatus.SERVING);
            long flipMs = Long.getLong("lab.health.flip.ms", 0L);
            if (flipMs > 0) {
                new java.util.Timer(true).schedule(new java.util.TimerTask() {
                    @Override
                    public void run() {
                        healthManager.setStatus("myxhs.lab.Greeter", ServingStatus.NOT_SERVING);
                        System.out.println("[lab-server] health 状态切换为 NOT_SERVING");
                    }
                }, flipMs);
            }
        }
        if (Boolean.getBoolean("lab.reflection")) {
            builder.addService(ProtoReflectionServiceV1.newInstance());
        }
        Server server = builder.build().start();
        System.out.println("[lab-server] started on " + port + "（services: Greeter + Echo + Streamer）");

        if (gracefulMs > 0) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("[lab-server] graceful shutdown, 等待 " + gracefulMs + "ms ...");
                server.shutdown();
                try {
                    if (!server.awaitTermination(gracefulMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        server.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    server.shutdownNow();
                }
                System.out.println("[lab-server] shutdown 完成");
            }));
        }
        server.awaitTermination();
    }
}
