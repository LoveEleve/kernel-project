package com.myxhs.dubbo.lab.naive;

import com.myxhs.dubbo.lab.api.DemoService;
import com.myxhs.dubbo.lab.api.User;

/**
 * 与 lab-provider 相同的接口、最简实现（同一接口两种传输的对照起点）。
 */
public class NaiveDemoServiceImpl implements DemoService {

    @Override
    public String sayHello(String name) {
        return "hello " + name + " from naive-rpc";
    }

    @Override
    public String slow(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "slept " + millis + "ms";
    }

    @Override
    public User getUser(long id) {
        return new User(id, "user-" + id);
    }
}
