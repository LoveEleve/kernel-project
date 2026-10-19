package com.scalab.rocketmq.a;

import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 顺序消费者——单队列串行，消费顺序=生产顺序。
 */
@Component
@RocketMQMessageListener(consumerGroup = "a03-orderly-group",
        topic = "A03-Orderly-Topic", consumeMode = ConsumeMode.ORDERLY)
public class A03OrderlyConsumer implements RocketMQListener<MessageExt> {

    private static final Logger log = LoggerFactory.getLogger(A03OrderlyConsumer.class);

    @Override
    public void onMessage(MessageExt msg) {
        log.info("[A03-ORDERLY] topic={} q={} offset={} body={}",
                msg.getTopic(), msg.getQueueId(), msg.getQueueOffset(),
                new String(msg.getBody(), java.nio.charset.StandardCharsets.UTF_8));
        A03ConsumeCompareController.recordOrderly(msg.getTopic(), msg.getQueueId(),
                new String(msg.getBody(), java.nio.charset.StandardCharsets.UTF_8));
    }
}
