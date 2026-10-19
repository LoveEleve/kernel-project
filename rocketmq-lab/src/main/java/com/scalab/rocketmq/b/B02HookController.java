package com.scalab.rocketmq.b;

import org.apache.rocketmq.client.hook.SendMessageContext;
import org.apache.rocketmq.client.hook.SendMessageHook;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * B02 — 发送 + 消费 Hook 验证。
 */
@RestController
@RequestMapping("/b02")
public class B02HookController {

    private static final Logger log = LoggerFactory.getLogger(B02HookController.class);

    private final DefaultMQProducer producer;
    private static final List<String> hookLog = new CopyOnWriteArrayList<>();

    public B02HookController(@Qualifier("labProducer") DefaultMQProducer producer) {
        this.producer = producer;
    }

    @PostConstruct
    public void registerHook() {
        producer.getDefaultMQProducerImpl().registerSendMessageHook(new SendMessageHook() {
            @Override
            public String hookName() { return "b02-send-hook"; }
            @Override
            public void sendMessageBefore(SendMessageContext ctx) {
                String body = new String(ctx.getMessage().getBody(), StandardCharsets.UTF_8);
                hookLog.add("SEND_BEFORE:" + body);
            }
            @Override
            public void sendMessageAfter(SendMessageContext ctx) {
                hookLog.add("SEND_AFTER:" + ctx.getSendResult().getMsgId());
            }
        });
        log.info("[B02] SendMessageHook registered");
    }

    @GetMapping("/send")
    public String send() throws Exception {
        Message msg = new Message("B02-Topic", "hook-test".getBytes(StandardCharsets.UTF_8));
        String msgId = producer.send(msg).getMsgId();
        return "SENT msgId=" + msgId;
    }

    @GetMapping("/log")
    public List<String> log() {
        return hookLog;
    }

    /** 消费 Hook——日志记录 */
    @Component
    @RocketMQMessageListener(consumerGroup = "b02-hook-group", topic = "B02-Topic")
    public static class HookConsumer implements RocketMQListener<org.apache.rocketmq.common.message.MessageExt> {
        @Override
        public void onMessage(org.apache.rocketmq.common.message.MessageExt msg) {
            hookLog.add("CONSUME:" + new String(msg.getBody(), StandardCharsets.UTF_8));
        }
    }
}
