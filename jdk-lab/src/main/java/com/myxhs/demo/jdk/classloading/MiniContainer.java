package com.myxhs.demo.jdk.classloading;

/**
 * 迷你"容器"：编译 MiniContainer 时，MyBiz 还不存在（或至少容器代码对它一无所知）。
 * 运行时按命令行传入的类名加载、造实例、调方法。
 *
 * 运行：
 *   java -cp target/classes com.myxhs.demo.jdk.classloading.MiniContainer \
 *        com.myxhs.demo.jdk.classloading.MyBiz
 *
 * 体验方式：先把 MyBiz.class 删掉再跑（ClassNotFoundException），
 * 编译进来再跑（成功）——这就是"程序必须处理编译期未知的类"的实物。
 */
public class MiniContainer {
    public static void main(String[] args) throws Exception {
        String className = args[0];                     // 类名来自命令行：编译 MiniContainer 时它还不存在
        Class<?> clazz = Class.forName(className);      // 运行时才去找这个类
        System.out.println("发现成员: " + java.util.Arrays.toString(clazz.getDeclaredMethods()));
        Object instance = clazz.getDeclaredConstructor().newInstance();   // 还不知道类长什么样，先造出来
        clazz.getMethod("hello").invoke(instance);                        // 按名字调它的方法
    }
}
