package com.scalab.rocketmq.a;

import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A09 — 重试与死信（DLQ）验证。
 *
 * <p>对应 D5 §四 失败处理 + D1 系统 Topic %RETRY%/%DLQ%。</p>
 */
@RestController
@RequestMapping("/a09")
public class A09RetryDlqController {

    private static final Logger log = LoggerFactory.getLogger(A09RetryDlqController.class);

    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    /** 消费记录 */
    public static final Map<String, String> status = new ConcurrentHashMap<>();

    /**
     * 发消息给重试 Topic——消费者会抛异常 N 次后成功。
     * GET /a09/send-retry?failCount=2
     */
    @GetMapping("/send-retry")
    public String sendRetry(@RequestParam(defaultValue = "2") int failCount) {
        if (rocketMQTemplate == null) return "SKIP";
        String key = "retry-" + System.currentTimeMillis();
        rocketMQTemplate.syncSend("A09-Retry-Topic",
                MessageBuilder.withPayload(failCount + "|" + key).build());
        return "SENT retry msg: key=" + key + " failCount=" + failCount;
    }

    /**
     * 发消息给 DLQ Topic——消费者永远抛异常。
     * GET /a09/send-dlq
     */
    @GetMapping("/send-dlq")
    public String sendDlq() {
        if (rocketMQTemplate == null) return "SKIP";
        String key = "dlq-" + System.currentTimeMillis();
        rocketMQTemplate.syncSend("A09-Dlq-Topic",
                MessageBuilder.withPayload(key).build());
        return "SENT dlq msg: key=" + key;
    }

    /** 查状态 */
    @GetMapping("/status")
    public Map<String, String> getStatus() {
        return status;
    }

    // ===== 消费者 =====

    /** 重试消费者——前 N 次抛异常，之后成功 */
    @Component
    @RocketMQMessageListener(consumerGroup = "a09-retry-group",
            topic = "A09-Retry-Topic", maxReconsumeTimes = 4)
    public static class RetryConsumer implements RocketMQListener<MessageExt> {
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public void onMessage(MessageExt msg) {
            String body = new String(msg.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            String[] parts = body.split("\\|");
            int failCount = Integer.parseInt(parts[0]);
            String key = parts[1];
            int times = counter.incrementAndGet();

            log.info("[A09-RETRY] key={} times={}/{} reconsumeTimes={}", key, times, failCount, msg.getReconsumeTimes());

            if (times <= failCount) {
                status.put(key, "RETRY#" + times + "/" + failCount);
                throw new RuntimeException("模拟业务失败:" + key + " #" + times);
            }
            status.put(key, "SUCCESS after " + times + " attempts");
        }
    }

    /** DLQ 消费者——永远抛异常，maxReconsumeTimes=2 加速进死信 */
    @Component
    @RocketMQMessageListener(consumerGroup = "a09-dlq-group",
            topic = "A09-Dlq-Topic", maxReconsumeTimes = 2)
    public static class DlqConsumer implements RocketMQListener<MessageExt> {
        @Override
        public void onMessage(MessageExt msg) {
            String body = new String(msg.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            log.info("[A09-DLQ] key={} reconsumeTimes={}", body, msg.getReconsumeTimes());
            status.put(body, "DLQ_ATTEMPT reconsumeTimes=" + msg.getReconsumeTimes());
            throw new RuntimeException("永远失败的毒丸:" + body);
        }
    }
}
