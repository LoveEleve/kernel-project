package com.myxhs.demo.jdk.classloading;

/**
 * MiniContainer 要处理的"编译期未知的类"。
 */
public class MyBiz {
    public void hello() {
        System.out.println("hello from MyBiz —— 我在 MiniContainer 编译完之后才出生");
    }
}
