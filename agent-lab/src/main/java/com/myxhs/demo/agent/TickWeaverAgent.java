package com.myxhs.demo.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** 织入 agent：给 TickApp.tick() 插入一行打印；支持 weave / undo 两种模式（参数切换） */
public class TickWeaverAgent {

    private static final String TARGET = "com/myxhs/demo/agent/TickApp";
    private static ClassFileTransformer INSTANCE;
    private static Instrumentation INST_AT_REGISTER;

    public static void agentmain(String args, Instrumentation inst) throws Exception {
        Class<?> target = Class.forName("com.myxhs.demo.agent.TickApp");

        if ("undo".equals(args)) {
            if (INST_AT_REGISTER != null && INSTANCE != null) {
                INST_AT_REGISTER.removeTransformer(INSTANCE);
                INST_AT_REGISTER.retransformClasses(target);
                System.out.println("[TickWeaver] 用【注册时那个】Instrumentation 移除并 retransform（应还原）");
                INSTANCE = null;
            } else {
                System.out.println("[TickWeaver] undo: 没有找到注册时的信息");
            }
            return;
        }

        System.out.println("[TickWeaver] agentmain: 注册 transformer ...");
        INSTANCE = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className,
                                    Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain,
                                    byte[] classfileBuffer) {
                if (!TARGET.equals(className)) {
                    return null;
                }
                System.out.println("[TickWeaver] transform 被调用: " + className
                        + (classBeingRedefined == null ? "  (新加载)" : "  (retransform)"));

                ClassReader cr = new ClassReader(classfileBuffer);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc,
                                                     String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!"tick".equals(name)) {
                            return mv;
                        }
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                mv.visitFieldInsn(Opcodes.GETSTATIC,
                                        "java/lang/System", "out", "Ljava/io/PrintStream;");
                                mv.visitLdcInsn("[Weaver] 织入的代码: tick 被调用了");
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
                            }
                        };
                    }
                };
                cr.accept(cv, 0);
                return cw.toByteArray();
            }
        };

        INST_AT_REGISTER = inst;
        inst.addTransformer(INSTANCE, true);
        System.out.println("[TickWeaver] TickApp 可修改吗 = " + inst.isModifiableClass(target));
        inst.retransformClasses(target);
        System.out.println("[TickWeaver] retransform 完成");
    }
}
