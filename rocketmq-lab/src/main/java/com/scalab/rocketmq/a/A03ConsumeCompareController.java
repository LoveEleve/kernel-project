package com.scalab.rocketmq.a;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A03 — 并发消费 vs 顺序消费对比。
 *
 * <p>并发消费者: 无队列锁,20线程池,消费顺序不定。
 *    顺序消费者: 单队列串行,消费顺序=生产顺序。</p>
 */
@RestController
@RequestMapping("/a03")
public class A03ConsumeCompareController {

    private static final Logger log = LoggerFactory.getLogger(A03ConsumeCompareController.class);

    /** 并发消费记录：topic → queueId → messages(received order) */
    private static final Map<String, Map<Integer, ConcurrentLinkedQueue<String>>> concurrentLog = new ConcurrentHashMap<>();
    /** 顺序消费记录 */
    private static final Map<String, Map<Integer, ConcurrentLinkedQueue<String>>> orderlyLog = new ConcurrentHashMap<>();

    static void recordConcurrent(String topic, int qid, String body) {
        concurrentLog.computeIfAbsent(topic, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(qid, k -> new ConcurrentLinkedQueue<>()).add(body);
    }

    static void recordOrderly(String topic, int qid, String body) {
        orderlyLog.computeIfAbsent(topic, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(qid, k -> new ConcurrentLinkedQueue<>()).add(body);
    }

    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    /**
     * 发 5 条到并发 Topic，不设 key → 分散到各队列，消费顺序不定。
     */
    @GetMapping("/send-concurrent")
    public String sendConcurrent() {
        if (rocketMQTemplate == null) return "SKIP";
        for (int i = 1; i <= 5; i++) {
            rocketMQTemplate.asyncSend("A03-Concurrent-Topic",
                    MessageBuilder.withPayload("concurrent-" + i).build(), null);
        }
        return "SENT 5 messages to A03-Concurrent-Topic (no key)";
    }

    /**
     * 发 5 条到顺序 Topic，同 key → 同队列，消费顺序=发送顺序。
     */
    @GetMapping("/send-orderly")
    public String sendOrderly() {
        if (rocketMQTemplate == null) return "SKIP";
        for (int i = 1; i <= 5; i++) {
            rocketMQTemplate.syncSendOrderly("A03-Orderly-Topic",
                    MessageBuilder.withPayload("orderly-" + i).build(), "same-key");
        }
        return "SENT 5 messages to A03-Orderly-Topic (same-key)";
    }

    /** 查并发消费记录 */
    @GetMapping("/log-concurrent")
    public List<String> logConcurrent() {
        Map<Integer, ConcurrentLinkedQueue<String>> perQ = concurrentLog.get("A03-Concurrent-Topic");
        if (perQ == null) return Collections.singletonList("(no messages)");
        List<String> result = new ArrayList<>();
        for (Map.Entry<Integer, ConcurrentLinkedQueue<String>> e : perQ.entrySet()) {
            result.add("q" + e.getKey() + " → " + new ArrayList<>(e.getValue()));
        }
        return result;
    }

    /** 查顺序消费记录 */
    @GetMapping("/log-orderly")
    public List<String> logOrderly() {
        Map<Integer, ConcurrentLinkedQueue<String>> perQ = orderlyLog.get("A03-Orderly-Topic");
        if (perQ == null) return Collections.singletonList("(no messages)");
        List<String> result = new ArrayList<>();
        for (Map.Entry<Integer, ConcurrentLinkedQueue<String>> e : perQ.entrySet()) {
            result.add("q" + e.getKey() + " → " + new ArrayList<>(e.getValue()));
        }
        return result;
    }
}
