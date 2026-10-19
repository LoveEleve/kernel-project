package com.scalab.rocketmq.a;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.annotation.SelectorType;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A07 — Tag 过滤验证。
 *
 * <p>对应 D1 §3.6 Tag 过滤 + D5 Broker 端 hash 粗筛 + Consumer 端精确比对。</p>
 */
@RestController
@RequestMapping("/a07")
public class A07TagFilterController {

    @Autowired
    @Qualifier("labProducer")
    private DefaultMQProducer defaultProducer;

    /** 消费记录 */
    public static final Map<String, ConcurrentLinkedQueue<String>> tagConsumed = new ConcurrentHashMap<>();

    static {
        tagConsumed.put("PAY", new ConcurrentLinkedQueue<>());
        tagConsumed.put("REFUND", new ConcurrentLinkedQueue<>());
    }

    /**
     * 发 3 条 PAY + 2 条 REFUND 到同一 Topic——消费者只订阅 PAY。
     */
    @GetMapping("/send")
    public String send() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Message msg = new Message("A07-Tag-Topic", "PAY", ("pay-" + i).getBytes(StandardCharsets.UTF_8));
            SendResult r = defaultProducer.send(msg);
            ids.add(r.getMsgId());
        }
        for (int i = 1; i <= 2; i++) {
            Message msg = new Message("A07-Tag-Topic", "REFUND", ("refund-" + i).getBytes(StandardCharsets.UTF_8));
            SendResult r = defaultProducer.send(msg);
            ids.add(r.getMsgId());
        }
        return "SENT: PAY(" + ids.get(0) + "," + ids.get(1) + "," + ids.get(2) +
                ") + REFUND(" + ids.get(3) + "," + ids.get(4) + ")";
    }

    @GetMapping("/log")
    public List<String> log() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, ConcurrentLinkedQueue<String>> e : tagConsumed.entrySet()) {
            result.add(e.getKey() + " → " + new ArrayList<>(e.getValue()));
        }
        return result;
    }

    /** PAY 标签消费者——只订阅 PAY */
    @Component
    @RocketMQMessageListener(consumerGroup = "a07-pay-group",
            topic = "A07-Tag-Topic", selectorExpression = "PAY", selectorType = SelectorType.TAG)
    public static class PayConsumer implements RocketMQListener<MessageExt> {
        private static final Logger log = LoggerFactory.getLogger(PayConsumer.class);
        @Override
        public void onMessage(MessageExt msg) {
            String body = new String(msg.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            log.info("[A07-PAY] q={} body={} tags={}", msg.getQueueId(), body, msg.getTags());
            tagConsumed.get("PAY").add(body);
        }
    }
}
