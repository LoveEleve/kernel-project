package com.scalab.rocketmq.a;

import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(consumerGroup = "a05-broadcast-group",
        topic = "A05-Broadcast-Topic", messageModel = MessageModel.BROADCASTING)
public class A05BroadcastConsumer implements RocketMQListener<MessageExt> {

    private static final Logger log = LoggerFactory.getLogger(A05BroadcastConsumer.class);

    @Override
    public void onMessage(MessageExt msg) {
        String body = new String(msg.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        log.info("[A05-BROADCAST] topic={} q={} body={}", msg.getTopic(), msg.getQueueId(), body);
        A05BroadcastController.broadcastLog.add(body);
    }
}
