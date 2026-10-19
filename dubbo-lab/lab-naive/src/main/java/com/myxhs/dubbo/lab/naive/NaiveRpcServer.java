package com.myxhs.dubbo.lab.naive;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 最简 RPC 服务端：一个连接一个线程，反射调用实现类。
 */
public class NaiveRpcServer {

    private final Object serviceImpl;
    private final int port;

    public NaiveRpcServer(Object serviceImpl, int port) {
        this.serviceImpl = serviceImpl;
        this.port = port;
    }

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("[naive-server] listening on " + port);
        while (true) {
            Socket socket = serverSocket.accept();          // BIO：一个连接
            new Thread(() -> handle(socket), "naive-worker").start();  // 一个线程
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            RpcRequest request = (RpcRequest) Frames.read(s.getInputStream());
            RpcResponse response = new RpcResponse();
            try {
                Method method = Class.forName(request.getInterfaceName())
                        .getMethod(request.getMethodName(), request.getParameterTypes());
                Object result = method.invoke(serviceImpl, request.getArguments());
                response.setResult(result);
                System.out.println("[naive-server] invoked " + request.getMethodName());
            } catch (Throwable t) {
                response.setError(t);
            }
            Frames.write(s.getOutputStream(), response);
        } catch (Exception e) {
            System.out.println("[naive-server] connection error: " + e);
        }
    }
}
