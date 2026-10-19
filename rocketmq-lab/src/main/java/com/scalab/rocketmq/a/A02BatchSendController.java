package com.scalab.rocketmq.a;

import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * A02 — 批量发送。
 *
 * <p>对应 D3 §六 批量发送四约束。</p>
 */
@RestController
@RequestMapping("/a02")
public class A02BatchSendController {

    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    @GetMapping("/batch")
    public String batchSend() {
        if (rocketMQTemplate == null) {
            return "SKIP: RocketMQTemplate not wired";
        }
        List<org.springframework.messaging.Message<?>> messages = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            messages.add(MessageBuilder.withPayload("batch-msg-" + i).build());
        }
        SendResult result;
        try {
            result = rocketMQTemplate.syncSend("A02-Batch-Topic", messages);
            return String.format("BATCH OK: msgId=%s, sendStatus=%s, queue=q%d, sent=5",
                    result.getMsgId(), result.getSendStatus(), result.getMessageQueue().getQueueId());
        } catch (Exception e) {
            return "BATCH FAIL: " + e.getMessage();
        }
    }

    @GetMapping("/batch-same-queue")
    public String batchSameQueue() {
        if (rocketMQTemplate == null) {
            return "SKIP: RocketMQTemplate not wired";
        }
        List<org.springframework.messaging.Message<?>> msgs = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            msgs.add(MessageBuilder.withPayload("batch-same-q-" + i).build());
        }
        // 用同一个 hashKey 保证同队列
        SendResult result = rocketMQTemplate.syncSendOrderly("A02-Batch-Topic", msgs, "same-key");
        return String.format("SAME-QUEUE OK: q=%s", result.getMessageQueue().getQueueId());
    }
}
