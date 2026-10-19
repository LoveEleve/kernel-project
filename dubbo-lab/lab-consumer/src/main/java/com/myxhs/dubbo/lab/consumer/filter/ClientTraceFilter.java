package com.myxhs.dubbo.lab.consumer.filter;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

/**
 * 自定义 Filter（消费方）：与提供方同名扩展点、不同模块各自注册。
 */
@Activate(group = CommonConstants.CONSUMER, order = -5000)
public class ClientTraceFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        long start = System.currentTimeMillis();
        System.out.println("[client-trace] 发起调用 " + invocation.getMethodName() + "（SPI 自定义 Filter 生效）");
        try {
            return invoker.invoke(invocation);
        } finally {
            System.out.println("[client-trace] 调用完成 " + invocation.getMethodName()
                    + " in " + (System.currentTimeMillis() - start) + "ms");
        }
    }
}
