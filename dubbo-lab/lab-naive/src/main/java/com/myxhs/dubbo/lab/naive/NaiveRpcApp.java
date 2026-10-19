package com.myxhs.dubbo.lab.naive;

import com.myxhs.dubbo.lab.api.DemoService;

/**
 * lab-naive 入口：-Dlab.mode=server | client（默认 client）。
 */
public class NaiveRpcApp {

    private static final int PORT = 20990;

    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("lab.mode", "client");
        if ("server".equals(mode)) {
            new NaiveRpcServer(new NaiveDemoServiceImpl(), PORT).start();
        } else {
            NaiveRpcClient client = new NaiveRpcClient("127.0.0.1", PORT, DemoService.class);
            DemoService demo = client.getProxy();
            System.out.println("[naive-client] sayHello = " + demo.sayHello("naive"));
            System.out.println("[naive-client] getUser  = " + demo.getUser(1L));
            System.out.println("[naive-client] done");
            System.exit(0);
        }
    }
}
