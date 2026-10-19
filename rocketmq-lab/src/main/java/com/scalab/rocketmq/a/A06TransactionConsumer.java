package com.scalab.rocketmq.a;

import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(consumerGroup = "a06-tx-consumer-group",
        topic = "A06-Tx-Topic")
public class A06TransactionConsumer implements RocketMQListener<MessageExt> {

    private static final Logger log = LoggerFactory.getLogger(A06TransactionConsumer.class);

    @Override
    public void onMessage(MessageExt msg) {
        String body = new String(msg.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        log.info("[A06-TX-CONSUMER] received: body={} ", body);
        A06TransactionConfig.txLog.put(body, "CONSUMED");
    }
}
