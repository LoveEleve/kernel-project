package com.myxhs.demo.jdk.reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 反射 accessor 的两代切换：NativeMethodAccessorImpl -> GeneratedMethodAccessor。
 *
 * 运行需要打开 JDK 内部包：
 *   java --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
 *        --add-opens java.base/jdk.internal.reflect=ALL-UNNAMED \
 *        -cp target/classes com.myxhs.demo.jdk.reflect.InflationDemo
 *
 * 观察点：前 15 次走 native；第 16 次起切换为运行时生成的字节码 accessor（"通胀"）。
 * 版本提示：这是 JDK 8~17 的实现；JDK 18 起核心反射改为方法句柄实现（JEP 416）。
 */
public class InflationDemo {

    public static void main(String[] args) throws Exception {
        Method m = InflationDemo.class.getDeclaredMethod("target");
        for (int i = 1; i <= 20; i++) {
            m.invoke(new InflationDemo());
            if (i == 1 || i == 14 || i == 15 || i == 16 || i == 17 || i == 20) {
                System.out.println("第 " + i + " 次调用后，accessor 链 = " + describe(m));
            }
        }
    }

    public void target() {
    }

    static String describe(Method m) throws Exception {
        Field f = Method.class.getDeclaredField("methodAccessor");
        f.setAccessible(true);
        Object acc = f.get(m);
        StringBuilder sb = new StringBuilder();
        while (acc != null) {
            sb.append(acc.getClass().getName());
            try {
                Field d = acc.getClass().getDeclaredField("delegate");
                d.setAccessible(true);
                Object next = d.get(acc);
                if (next == null) {
                    break;
                }
                sb.append(" -> ");
                acc = next;
            } catch (NoSuchFieldException e) {
                break;
            }
        }
        return sb.toString();
    }
}
