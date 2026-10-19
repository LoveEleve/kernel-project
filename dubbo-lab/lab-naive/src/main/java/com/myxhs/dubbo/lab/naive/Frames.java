package com.myxhs.dubbo.lab.naive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

/**
 * 最简协议：4 字节长度前缀 + Java 序列化字节。
 * <p>
 * 为什么要有长度前缀？TCP 是字节流——没有边界。直接 readObject 会因为
 * 半包/粘包读到半个对象或读到下一个请求的开头。长度前缀就是最朴素的"消息边界"。
 * </p>
 */
final class Frames {

    private Frames() {
    }

    static void write(OutputStream out, Object obj) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(obj);
        }
        byte[] data = bos.toByteArray();
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(data.length);   // 先写长度
        dos.write(data);             // 再写内容
        dos.flush();
    }

    static Object read(InputStream in) throws IOException, ClassNotFoundException {
        DataInputStream dis = new DataInputStream(in);
        int len = dis.readInt();     // 先读长度
        byte[] data = new byte[len];
        dis.readFully(data);         // 按长度读满（解决半包）
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return ois.readObject();
        }
    }
}
