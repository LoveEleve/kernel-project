package com.myxhs.demo.jdk.reflect;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 迷你 MyBatis：框架只拿到"类名字符串 + 列值"，靠反射造对象、按字段名填值。
 *
 * 观察点：
 * 1) 第一遍不带 setAccessible 会抛 IllegalAccessException —— 反射没有特权，默认受访问控制约束
 * 2) 整个 mapToObject 里没有任何地方知道 User 的编译期类型
 */
public class ReflectionBasics {

    public static void main(String[] args) throws Exception {
        // 模拟数据库查到的一行：列名 -> 值（框架运行时才拿到这份数据）
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1001L);
        row.put("name", "microsphere");

        // 框架手里只有一个类名字符串（全限定名）
        String className = "com.myxhs.demo.jdk.reflect.User";

        System.out.println("-- 第一遍：不做 setAccessible（模拟框架漏掉了这一步）--");
        try {
            System.out.println(mapToObject(className, row, false));
        } catch (Exception e) {
            System.out.println("失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        System.out.println("-- 第二遍：加 setAccessible(true) --");
        System.out.println(mapToObject(className, row, true));
    }

    static Object mapToObject(String className, Map<String, Object> row, boolean accessible) throws Exception {
        Class<?> type = Class.forName(className);                                  // 1. 按名字找到"类档案"
        Object instance = type.getDeclaredConstructor().newInstance();             // 2. 造一个实例
        for (Map.Entry<String, Object> e : row.entrySet()) {
            Field f = type.getDeclaredField(e.getKey());                           // 3. 按名字找到字段
            if (accessible) {
                f.setAccessible(true);                                             // 4. 私有字段先"解锁"
            }
            f.set(instance, e.getValue());                                         // 5. 写入值
        }
        return instance;
    }
}

class User {
    private Long id;
    private String name;

    @Override
    public String toString() {
        return "User{id=" + id + ", name='" + name + "'}";
    }
}
