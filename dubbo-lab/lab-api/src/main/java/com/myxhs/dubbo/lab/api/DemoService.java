package com.myxhs.dubbo.lab.api;

/**
 * lab 通用演示接口：所有实验共用。
 * <p>
 * 刻意保持"业务无关"——每一篇 Dubbo 文章需要验证的机制，都通过扩展这个接口或新增接口来完成。
 * </p>
 */
public interface DemoService {

    /** 最小调用：验证一次 RPC 往返 */
    String sayHello(String name);

    /** 慢调用：验证 timeout / 异步 / 线程池（provider 会 sleep 指定毫秒） */
    String slow(int millis);

    /** 对象传输：验证序列化 */
    User getUser(long id);
}
