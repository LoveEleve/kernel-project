package com.scalab.rocketmq.a;

import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 并发消费者——无队列锁，多线程并行消费，消费顺序不定。
 */
@Component
@RocketMQMessageListener(consumerGroup = "a03-concurrent-group",
        topic = "A03-Concurrent-Topic")
public class A03ConcurrentConsumer implements RocketMQListener<MessageExt> {

    private static final Logger log = LoggerFactory.getLogger(A03ConcurrentConsumer.class);

    @Override
    public void onMessage(MessageExt msg) {
        log.info("[A03-CONCURRENT] topic={} q={} offset={} body={}",
                msg.getTopic(), msg.getQueueId(), msg.getQueueOffset(),
                new String(msg.getBody(), java.nio.charset.StandardCharsets.UTF_8));
        A03ConsumeCompareController.recordConcurrent(msg.getTopic(), msg.getQueueId(),
                new String(msg.getBody(), java.nio.charset.StandardCharsets.UTF_8));
    }
}
