package com.myxhs.dubbo.lab.naive;

import java.lang.reflect.Proxy;
import java.net.Socket;

/**
 * 最简 RPC 客户端：JDK 动态代理把接口方法调用变成一次 Socket 往返。
 * <p>
 * 注意代理做了什么、没做什么：
 * - 做了：把 method/args 打包成 RpcRequest，发送，等待 RpcResponse；
 * - 没做：超时、重试、地址发现、连接复用、协议协商——这些正是后续篇章要补的。
 * </p>
 */
public class NaiveRpcClient {

    private final String host;
    private final int port;
    private final Class<?> interfaceClass;

    public NaiveRpcClient(String host, int port, Class<?> interfaceClass) {
        this.host = host;
        this.port = port;
        this.interfaceClass = interfaceClass;
    }

    @SuppressWarnings("unchecked")
    public <T> T getProxy() {
        return (T) Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[]{interfaceClass},
                (proxy, method, args) -> {
                    RpcRequest request = new RpcRequest(
                            interfaceClass.getName(), method.getName(),
                            method.getParameterTypes(), args);
                    try (Socket socket = new Socket(host, port)) {   // 每次调用新建连接
                        Frames.write(socket.getOutputStream(), request);
                        RpcResponse response = (RpcResponse) Frames.read(socket.getInputStream());
                        if (response.getError() != null) {
                            throw response.getError();
                        }
                        return response.getResult();
                    }
                });
    }
}
