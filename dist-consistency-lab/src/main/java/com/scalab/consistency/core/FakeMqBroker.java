package com.scalab.consistency.core;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 假 MQ Broker——自包含实验用（无需外部 RocketMQ）。
 *
 * <p>职责：接收 Outbox 补发/直发消息；支持"注入故障"（模拟 Broker 宕机），
 * 用于演示 Outbox 补发的核心价值：Broker 挂掉时消息不丢（本地消息表兜底）。
 */
@Component
public class FakeMqBroker {

    /** 故障开关：true = 发送必失败（模拟 Broker 宕机） */
    private final AtomicBoolean failureInjected = new AtomicBoolean(false);

    /** 收到的消息（topic → 消息体列表） */
    private final Map<String, List<String>> received = new ConcurrentHashMap<>();

    /** 发送尝试日志（含失败） */
    private final ConcurrentLinkedQueue<String> attempts = new ConcurrentLinkedQueue<>();

    /** 发送；注入故障时抛异常（调用方按失败处理）。 */
    public void send(String topic, String payload) {
        attempts.add("topic=" + topic + " payload=" + payload
                + (failureInjected.get() ? " -> FAILED(injected)" : " -> OK"));
        if (failureInjected.get()) {
            throw new IllegalStateException("FakeMqBroker: broker unavailable (failure injected)");
        }
        received.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(payload);
    }

    public void injectFailure(boolean enabled) {
        failureInjected.set(enabled);
    }

    public boolean isFailureInjected() {
        return failureInjected.get();
    }

    public Map<String, List<String>> receivedMessages() {
        return received;
    }

    public List<String> attemptLog() {
        return List.copyOf(attempts);
    }

    public void reset() {
        failureInjected.set(false);
        received.clear();
        attempts.clear();
    }
}
