package com.myxhs.demo.agent;

import com.sun.tools.attach.VirtualMachine;

/** 装载客户端：attach 到目标进程 + loadAgent（发 load 命令） */
public class AttachLoadAgent {
    public static void main(String[] args) throws Exception {
        String pid = args[0];
        String agentJar = args[1];
        String options = args.length > 2 ? args[2] : "";
        System.out.println("[client] attaching to pid=" + pid);
        VirtualMachine vm = VirtualMachine.attach(pid);
        try {
            vm.loadAgent(agentJar, options);
            System.out.println("[client] loadAgent done: " + agentJar);
        } finally {
            vm.detach();
        }
    }
}
