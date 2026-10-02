package com.myxhs.demo.jdk.reflect;

import java.lang.reflect.Method;

/**
 * 四路径耗时对比：直接调用 / 缓存反射 / 每次重查找 / 多态调用点。
 *
 * 运行：
 *   java -cp target/classes com.myxhs.demo.jdk.reflect.PerfDemo
 *   java -Dsun.reflect.inflationThreshold=100000000 -cp target/classes ...PerfDemo   # 强制走 native 对照（慢约 16 倍）
 *
 * 注意：这是微基准，只看数量级（JIT 会内联/消除，数字会浮动）；严肃基准请上 JMH。
 */
public class PerfDemo {
    static long sink = 0;
    static final int N = 2_000_000;

    public static void main(String[] args) throws Exception {
        PerfDemo demo = new PerfDemo();
        Method cached = PerfDemo.class.getDeclaredMethod("hello");
        Method[] poly = {
                PerfDemo.class.getDeclaredMethod("hello"),
                PerfDemo.class.getDeclaredMethod("hello1"),
                PerfDemo.class.getDeclaredMethod("hello2"),
                PerfDemo.class.getDeclaredMethod("hello3"),
        };

        // 预热：让 JIT 编译热路径，也让反射 accessor 完成切换（>=15 次）
        for (int i = 0; i < 200_000; i++) {
            demo.hello();
            cached.invoke(demo);
            poly[i & 3].invoke(demo);
        }

        // 每项跑两遍，取第二遍（第一遍触发该路径自身的热编译）
        runDirect(demo);
        long direct = runDirect(demo);
        runCached(cached, demo);
        long reflect = runCached(cached, demo);
        runPoly(poly, demo);
        long polyNs = runPoly(poly, demo);
        runFind(demo);
        long find = runFind(demo);

        System.out.printf("① 直接调用:            %.1f ns/次%n", direct / (double) N);
        System.out.printf("② 反射(缓存 Method):   %.1f ns/次%n", reflect / (double) N);
        System.out.printf("③ 反射(每次重查找):    %.1f ns/次%n", find / (double) N);
        System.out.printf("④ 反射(多态调用点):    %.1f ns/次%n", polyNs / (double) N);
    }

    static long runDirect(PerfDemo demo) {
        long t = System.nanoTime();
        for (int i = 0; i < N; i++) {
            demo.hello();
        }
        return System.nanoTime() - t;
    }

    static long runCached(Method m, PerfDemo demo) throws Exception {
        long t = System.nanoTime();
        for (int i = 0; i < N; i++) {
            m.invoke(demo);
        }
        return System.nanoTime() - t;
    }

    static long runPoly(Method[] ms, PerfDemo demo) throws Exception {
        long t = System.nanoTime();
        for (int i = 0; i < N; i++) {
            ms[i & 3].invoke(demo);
        }
        return System.nanoTime() - t;
    }

    static long runFind(PerfDemo demo) throws Exception {
        long t = System.nanoTime();
        for (int i = 0; i < N; i++) {
            PerfDemo.class.getDeclaredMethod("hello").invoke(demo);
        }
        return System.nanoTime() - t;
    }

    public void hello() {
        sink++;
    }

    public void hello1() {
        sink++;
    }

    public void hello2() {
        sink++;
    }

    public void hello3() {
        sink++;
    }
}
