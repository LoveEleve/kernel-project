package com.myxhs.demo.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** 反证实验：尝试给 TickApp 新增一个方法 → 观察 JVM 拒绝（结构冻结，方法体可换） */
public class AddMethodAgent {

    private static final String TARGET = "com/myxhs/demo/agent/TickApp";

    public static void agentmain(String args, Instrumentation inst) throws Exception {
        System.out.println("[AddMethod] agentmain: 注册 transformer（会给 TickApp 新增一个方法）");

        ClassFileTransformer t = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className,
                                    Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain,
                                    byte[] classfileBuffer) {
                if (!TARGET.equals(className)) {
                    return null;
                }
                System.out.println("[AddMethod] transform 被调用 (" + (classBeingRedefined == null ? "新加载" : "retransform")
                        + ")，尝试新增方法 wovenByAgent()");

                ClassReader cr = new ClassReader(classfileBuffer);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public void visitEnd() {
                        // 给类"加一个新方法"（redefinition 不允许的事）
                        MethodVisitor mv = super.visitMethod(
                                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "wovenByAgent", "()V", null, null);
                        mv.visitCode();
                        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                        mv.visitLdcInsn("[AddMethod] 我是 agent 新加的方法");
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream",
                                "println", "(Ljava/lang/String;)V", false);
                        mv.visitInsn(Opcodes.RETURN);
                        mv.visitMaxs(0, 0);
                        mv.visitEnd();
                        super.visitEnd();
                    }
                };
                cr.accept(cv, 0);
                return cw.toByteArray();
            }
        };

        inst.addTransformer(t, true);
        Class<?> target = Class.forName("com.myxhs.demo.agent.TickApp");
        try {
            inst.retransformClasses(target);
            System.out.println("[AddMethod] 竟然成功了？不应该！");
        } catch (Throwable e) {
            System.out.println("[AddMethod] retransform 被拒绝: " + e);
        }
    }
}
