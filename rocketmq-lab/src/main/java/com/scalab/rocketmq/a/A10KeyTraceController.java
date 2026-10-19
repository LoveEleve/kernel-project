package com.scalab.rocketmq.a;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A10 — Key 消息查询 + 消息轨迹验证。
 *
 * <p>发送时设 KEYS，消费端通过 Key 校验消息完整性。轨迹另靠 enableMsgTrace 配置。
 */
@RestController
@RequestMapping("/a10")
public class A10KeyTraceController {

    @Autowired
    @Qualifier("labProducer")
    private DefaultMQProducer producer;

    public static final Map<String, ConcurrentLinkedQueue<String>> keyConsumed = new ConcurrentHashMap<>();

    @GetMapping("/send")
    public String send() throws Exception {
        keyConsumed.clear();
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Message msg = new Message("A10-Key-Topic", ("key-body-" + i).getBytes(StandardCharsets.UTF_8));
            msg.setKeys("BIZ-" + i);
            SendResult r = producer.send(msg);
            ids.add(r.getMsgId() + "(key=BIZ-" + i + ")");
        }
        return "SENT 3 messages with KEYS: " + String.join(", ", ids);
    }

    @GetMapping("/log")
    public Map<String, List<String>> log() {
        Map<String, List<String>> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, ConcurrentLinkedQueue<String>> e : keyConsumed.entrySet()) {
            result.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return result;
    }

    /** Key 消费者——记录收到的 key+body */
    @Component
    @RocketMQMessageListener(consumerGroup = "a10-key-group",
            topic = "A10-Key-Topic")
    public static class KeyConsumer implements RocketMQListener<MessageExt> {
        private static final Logger log = LoggerFactory.getLogger(KeyConsumer.class);

        @Override
        public void onMessage(MessageExt msg) {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            String keys = msg.getKeys();
            log.info("[A10-KEY] keys={} body={}", keys, body);
            keyConsumed.computeIfAbsent(keys, k -> new ConcurrentLinkedQueue<>()).add(body);
        }
    }
}
