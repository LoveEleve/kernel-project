package com.myxhs.dubbo.lab.provider.filter;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

/**
 * 自定义 Filter（提供方）：验证 Dubbo SPI——加一个类 + 一个 SPI 文件，框架自动装配，不改框架代码。
 */
@Activate(group = CommonConstants.PROVIDER, order = -5000)
public class TraceFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        long start = System.currentTimeMillis();
        System.out.println("[trace] provider 收到 " + invocation.getMethodName() + "（SPI 自定义 Filter 生效）");
        try {
            return invoker.invoke(invocation);
        } finally {
            System.out.println("[trace] provider 完成 " + invocation.getMethodName()
                    + " in " + (System.currentTimeMillis() - start) + "ms");
        }
    }
}
