package com.myxhs.dubbo.lab.api;

import java.io.Serializable;
import java.util.Objects;

/**
 * 序列化实验用模型：必须可序列化（Dubbo 默认 hessian2 不要求 Serializable，但保持 Java 语义清晰）。
 */
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    private long id;
    private String name;

    public User() {
    }

    public User(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof User)) {
            return false;
        }
        User user = (User) o;
        return id == user.id && Objects.equals(name, user.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name);
    }

    @Override
    public String toString() {
        return "User{id=" + id + ", name='" + name + "'}";
    }
}
