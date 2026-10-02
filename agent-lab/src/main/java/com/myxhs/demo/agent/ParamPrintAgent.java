package com.myxhs.demo.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** 织入"打印方法入参"：在 TickApp.tick() 入口读取局部变量表 0 号槽（参数 n）并打印 */
public class ParamPrintAgent {

    private static final String TARGET = "com/myxhs/demo/agent/TickApp";

    public static void agentmain(String args, Instrumentation inst) throws Exception {
        ClassFileTransformer t = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className,
                                    Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain,
                                    byte[] classfileBuffer) {
                if (!TARGET.equals(className)) {
                    return null;
                }
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
                                // 织入：System.out.print("[ParamPrint] tick n=");
                                mv.visitFieldInsn(Opcodes.GETSTATIC,
                                        "java/lang/System", "out", "Ljava/io/PrintStream;");
                                mv.visitLdcInsn("[ParamPrint] tick n=");
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "java/io/PrintStream", "print", "(Ljava/lang/String;)V", false);
                                // 织入：System.out.println(n);    ← n 就是入参
                                mv.visitFieldInsn(Opcodes.GETSTATIC,
                                        "java/lang/System", "out", "Ljava/io/PrintStream;");
                                mv.visitVarInsn(Opcodes.ILOAD, 0);   // 读局部变量表 0 号槽（static 方法的第一个参数）
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "java/io/PrintStream", "println", "(I)V", false);
                            }
                        };
                    }
                };
                cr.accept(cv, 0);
                return cw.toByteArray();
            }
        };
        inst.addTransformer(t, true);
        inst.retransformClasses(Class.forName("com.myxhs.demo.agent.TickApp"));
        System.out.println("[ParamPrint] 织入完成：tick 现在会打印入参");
    }
}
