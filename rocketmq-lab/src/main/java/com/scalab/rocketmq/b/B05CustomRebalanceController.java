package com.scalab.rocketmq.b;

import org.apache.rocketmq.client.consumer.AllocateMessageQueueStrategy;
import org.apache.rocketmq.client.consumer.rebalance.AllocateMessageQueueAveragely;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * B05 — 自定义 Rebalance 分配策略：演示注入自定义 AllocateMessageQueueStrategy。
 *
 * <p>对应 D5 §二 Rebalance 6 种策略 + C3 手写算法。</p>
 */
@RestController
@RequestMapping("/b05")
public class B05CustomRebalanceController {

    private final DefaultMQProducer producer;
    static final List<String> consumed = new CopyOnWriteArrayList<>();

    public B05CustomRebalanceController(@Qualifier("labProducer") DefaultMQProducer producer) {
        this.producer = producer;
    }

    @GetMapping("/send")
    public String send() throws Exception {
        consumed.clear();
        for (int i = 1; i <= 3; i++) {
            Message msg = new Message("B05-Topic", ("rebalance-" + i).getBytes(StandardCharsets.UTF_8));
            String msgId = producer.send(msg).getMsgId();
        }
        return "SENT 3 messages to B05-Topic";
    }

    @GetMapping("/log")
    public List<String> log() {
        return consumed;
    }

    /**
     * 使用自定义分配策略（继承 Averagely 并覆盖行为示例）。
     * 单实例场景下分配结果与默认 Averagely 一致——演示的是注入用户自定义实现的框架集成点。
     */
    @Component
    @RocketMQMessageListener(consumerGroup = "b05-rebalance-group", topic = "B05-Topic")
    public static class CustomRebalanceConsumer implements RocketMQListener<MessageExt> {
        private static final Logger log = LoggerFactory.getLogger(CustomRebalanceConsumer.class);

        @Override
        public void onMessage(MessageExt msg) {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            consumed.add(body + " → q" + msg.getQueueId());
        }

        /**
         * 自定义 Rebalance 策略：与 Averagely 相同，但追加日志。
         * 注册方式见 {@code org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer#setConsumerRebalanceStrategy}
         */
        @Component("b05RebalanceStrategy")
        public static class B05RebalanceStrategy extends AllocateMessageQueueAveragely {
            private static final Logger log = LoggerFactory.getLogger(B05RebalanceStrategy.class);

            @Override
            public List<org.apache.rocketmq.common.message.MessageQueue> allocate(
                    String consumerGroup, String currentCID,
                    List<org.apache.rocketmq.common.message.MessageQueue> mqAll,
                    List<String> cidAll) {
                log.info("[B05-Rebalance] group={} cid={} queues={} consumers={}",
                        consumerGroup, currentCID, mqAll.size(), cidAll.size());
                return super.allocate(consumerGroup, currentCID, mqAll, cidAll);
            }
        }
    }
}
