package com.scalab.rocketmq.b;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * B01 — 自定义 QueueSelector：按业务键的绝对值取模选队列。
 */
@RestController
@RequestMapping("/b01")
public class B01QueueSelectorController {

    private final DefaultMQProducer producer;

    public B01QueueSelectorController(@Qualifier("labProducer") DefaultMQProducer producer) {
        this.producer = producer;
    }

    @GetMapping("/send")
    public String send(@RequestParam(defaultValue = "100") long bizId) throws Exception {
        Message msg = new Message("B01-Topic", ("biz-" + bizId).getBytes(StandardCharsets.UTF_8));
        // 自定义选择器：按 bizId 绝对值取模
        MessageQueueSelector selector = (List<MessageQueue> mqs, Message m, Object arg) -> {
            long id = (Long) arg;
            int index = Math.abs((int) (id % mqs.size()));
            return mqs.get(index);
        };
        MessageQueue selected = producer.send(msg, selector, bizId).getMessageQueue();
        return String.format("SENT bizId=%d → q=%d", bizId, selected.getQueueId());
    }
}
