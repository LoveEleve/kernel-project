package com.myxhs.dubbo.lab.naive;

import java.io.Serializable;

/**
 * 最简 RPC 请求：接口名 + 方法名 + 参数类型 + 参数值。
 * <p>
 * 为什么方法调用要拆成这四样？因为跨进程后，"调用哪个对象的哪个方法、传什么参"
 * 只能以数据的形式表达——这就是"调用信息序列化"。
 * </p>
 */
public class RpcRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String interfaceName;
    private String methodName;
    private Class<?>[] parameterTypes;
    private Object[] arguments;

    public RpcRequest() {
    }

    public RpcRequest(String interfaceName, String methodName,
                      Class<?>[] parameterTypes, Object[] arguments) {
        this.interfaceName = interfaceName;
        this.methodName = methodName;
        this.parameterTypes = parameterTypes;
        this.arguments = arguments;
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public String getMethodName() {
        return methodName;
    }

    public Class<?>[] getParameterTypes() {
        return parameterTypes;
    }

    public Object[] getArguments() {
        return arguments;
    }
}
