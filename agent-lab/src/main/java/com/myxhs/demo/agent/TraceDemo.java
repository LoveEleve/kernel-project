package com.myxhs.demo.agent;

import java.io.FileInputStream;
import java.io.PrintWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.ASMifier;
import org.objectweb.asm.util.TraceClassVisitor;

/**
 * ASM 事件流打印工具（学习用）：
 *   java ... TraceDemo <class文件路径>           → 人类可读视图
 *   java ... TraceDemo <class文件路径> events    → 事件形态（visitXxx 调用）
 */
public class TraceDemo {
    public static void main(String[] args) throws Exception {
        ClassReader cr = new ClassReader(new FileInputStream(args[0]));
        if (args.length > 1 && args[1].equals("events")) {
            cr.accept(new TraceClassVisitor(null, new ASMifier(), new PrintWriter(System.out, true)), 0);
        } else {
            cr.accept(new TraceClassVisitor(new PrintWriter(System.out, true)), 0);
        }
    }
}
