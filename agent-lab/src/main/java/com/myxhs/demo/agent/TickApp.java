package com.myxhs.demo.agent;

/** 靶子程序：每秒调用一次 tick()，方便被 agent 织入观察 */
public class TickApp {
    public static void main(String[] args) throws Exception {
        System.out.println("[TickApp] start pid=" + ProcessHandle.current().pid());
        int n = 0;
        while (true) {
            tick(++n);
            Thread.sleep(1000);
        }
    }

    static void tick(int n) {
        System.out.println("[TickApp] tick " + n);
    }
}
