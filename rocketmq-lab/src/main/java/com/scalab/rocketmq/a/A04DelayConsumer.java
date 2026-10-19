package com.scalab.rocketmq.a;

import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(consumerGroup = "a04-delay-group",
        topic = "A04-Delay-Topic")
public class A04DelayConsumer implements RocketMQListener<MessageExt> {

    private static final Logger log = LoggerFactory.getLogger(A04DelayConsumer.class);

    @Override
    public void onMessage(MessageExt msg) {
        long now = System.currentTimeMillis();
        String body = new String(msg.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        log.info("[A04-DELAY] q={} offset={} body={} now={}", msg.getQueueId(), msg.getQueueOffset(), body, now);
        A04DelayController.delayResults.put(body, now);
    }
}
