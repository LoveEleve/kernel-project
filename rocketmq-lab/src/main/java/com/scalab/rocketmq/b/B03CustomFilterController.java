package com.scalab.rocketmq.b;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * B03 — 自定义过滤器：按消息自定义属性过滤（如 priority=HIGH 才处理）。
 */
@RestController
@RequestMapping("/b03")
public class B03CustomFilterController {

    private final DefaultMQProducer producer;
    static final List<String> consumed = new CopyOnWriteArrayList<>();

    public B03CustomFilterController(@Qualifier("labProducer") DefaultMQProducer producer) {
        this.producer = producer;
    }

    @GetMapping("/send")
    public String send() throws Exception {
        consumed.clear();
        String[] priorities = {"HIGH", "LOW", "HIGH", "LOW", "HIGH"};
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < priorities.length; i++) {
            Message msg = new Message("B03-Topic", ("msg-" + (i + 1)).getBytes(StandardCharsets.UTF_8));
            msg.putUserProperty("priority", priorities[i]);
            String msgId = producer.send(msg).getMsgId();
            ids.add(msgId + "(priority=" + priorities[i] + ")");
        }
        return "SENT: " + String.join(", ", ids);
    }

    @GetMapping("/log")
    public List<String> log() {
        return consumed;
    }

    /**
     * 自定义过滤消费者——只处理 priority=HIGH 的消息，其他丢弃。
     */
    @Component
    @RocketMQMessageListener(consumerGroup = "b03-filter-group", topic = "B03-Topic")
    public static class FilterConsumer implements RocketMQListener<MessageExt> {
        private static final Logger log = LoggerFactory.getLogger(FilterConsumer.class);

        @Override
        public void onMessage(MessageExt msg) {
            String priority = msg.getProperty("priority");
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            if ("HIGH".equals(priority)) {
                consumed.add(body + "(HIGH)");
                log.info("[B03] processed: {} priority=HIGH", body);
            } else {
                log.info("[B03] SKIPPED: {} priority=LOW (custom filter)", body);
            }
        }
    }
}
