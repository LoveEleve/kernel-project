package com.myxhs.demo.jdk.reflect;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 反射的类型体系：Class / ParameterizedType / TypeVariable / WildcardType / GenericArrayType，
 * 以及"泛型变量代换"（框架解析泛型的核心动作）。
 *
 * 观察点：
 * 1) getType() 只给擦除后的裸类型；getGenericType() 才给完整类型树
 * 2) 类型变量（T/K）只是占位符，必须沿继承链代换成实际类型（resolveName / lookup）
 * 3) 类型树可以是环（K extends Comparable<K>）—— 解析时要防无限递归（见 describe 的 seen 集合）
 * 4) 实例身上没有泛型信息：new ArrayList<String>() 的类只是 ArrayList
 */
public class TypeDemo {

    static class Parent<T extends Number> {
        T value;
        List<T> list;
    }

    static class Child extends Parent<Integer> {
    }

    List<String> plain;
    List<? extends Number> extWildcard;
    List<? super Integer> superWildcard;
    Map<String, List<Integer>> nested;
    List<String>[] genericArray;

    public <K extends Comparable<K>> void method(K key, Map<K, List<?>> data) {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("===== 1. 字段的泛型类型树 =====");
        for (Field f : TypeDemo.class.getDeclaredFields()) {
            System.out.println(f.getName());
            System.out.println("  getType()        = " + f.getType().getTypeName());
            System.out.println("  getGenericType() = " + describe(f.getGenericType()));
        }

        System.out.println();
        System.out.println("===== 2. 方法参数上的泛型 =====");
        for (Type p : TypeDemo.class.getMethod("method", Comparable.class, Map.class).getGenericParameterTypes()) {
            System.out.println("  " + describe(p));
        }

        System.out.println();
        System.out.println("===== 3. 泛型代换：Child extends Parent<Integer> =====");
        Type superType = Child.class.getGenericSuperclass();
        System.out.println("  Child 的父类            = " + describe(superType));
        Type declared = Parent.class.getDeclaredField("value").getGenericType();
        System.out.println("  Parent.value 的声明类型 = " + describe(declared));
        System.out.println("  在 Child 语境下解析     = " + resolveName(declared, Child.class));
        Type declaredList = Parent.class.getDeclaredField("list").getGenericType();
        System.out.println("  Parent.list 的声明类型  = " + describe(declaredList));
        System.out.println("  在 Child 语境下解析     = " + resolveName(declaredList, Child.class));

        System.out.println();
        System.out.println("===== 4. 边界：实例身上没有泛型信息 =====");
        List<String> instance = new ArrayList<>();
        System.out.println("  实例的类                = " + instance.getClass().getName());
        System.out.println("  ArrayList 声明的类型参数 = " + Arrays.toString(ArrayList.class.getTypeParameters()));
    }

    static String describe(Type t) {
        return describe(t, new HashSet<>());
    }

    static String describe(Type t, Set<TypeVariable<?>> seen) {
        if (t instanceof Class) {
            return "Class(" + ((Class<?>) t).getSimpleName() + ")";
        }
        if (t instanceof ParameterizedType) {
            ParameterizedType p = (ParameterizedType) t;
            StringBuilder sb = new StringBuilder("ParameterizedType(" + ((Class<?>) p.getRawType()).getSimpleName());
            for (Type a : p.getActualTypeArguments()) {
                sb.append(", ").append(describe(a, seen));
            }
            return sb.append(")").toString();
        }
        if (t instanceof TypeVariable) {
            TypeVariable<?> v = (TypeVariable<?>) t;
            if (!seen.add(v)) {
                return "TypeVariable(" + v.getName() + ")";   // 自引用边界：只打名字，防无限递归
            }
            StringBuilder sb = new StringBuilder("TypeVariable(" + v.getName() + " extends ");
            Type[] bounds = v.getBounds();
            for (int i = 0; i < bounds.length; i++) {
                if (i > 0) {
                    sb.append(" & ");
                }
                sb.append(describe(bounds[i], seen));
            }
            return sb.append(")").toString();
        }
        if (t instanceof WildcardType) {
            WildcardType w = (WildcardType) t;
            return "WildcardType(upper=" + Arrays.toString(w.getUpperBounds())
                    + ", lower=" + Arrays.toString(w.getLowerBounds()) + ")";
        }
        if (t instanceof GenericArrayType) {
            return "GenericArrayType(" + describe(((GenericArrayType) t).getGenericComponentType(), seen) + "[])";
        }
        return t.getClass().getSimpleName();
    }

    static String resolveName(Type type, Class<?> context) {
        if (type instanceof TypeVariable) {
            Type actual = lookup((TypeVariable<?>) type, context);
            return actual == type ? ((TypeVariable<?>) type).getName() : resolveName(actual, context);
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType p = (ParameterizedType) type;
            StringBuilder sb = new StringBuilder(((Class<?>) p.getRawType()).getSimpleName()).append('<');
            Type[] args = p.getActualTypeArguments();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(resolveName(args[i], context));
            }
            return sb.append('>').toString();
        }
        if (type instanceof GenericArrayType) {
            return resolveName(((GenericArrayType) type).getGenericComponentType(), context) + "[]";
        }
        return type.getTypeName();
    }

    static Type lookup(TypeVariable<?> var, Class<?> context) {
        Class<?> c = context;
        while (c != null) {
            Type gs = c.getGenericSuperclass();
            if (gs instanceof ParameterizedType) {
                ParameterizedType p = (ParameterizedType) gs;
                TypeVariable<?>[] vars = ((Class<?>) p.getRawType()).getTypeParameters();
                Type[] args = p.getActualTypeArguments();
                for (int i = 0; i < vars.length; i++) {
                    if (vars[i].equals(var)) {
                        return args[i];
                    }
                }
            }
            c = c.getSuperclass();
        }
        return var;
    }
}
