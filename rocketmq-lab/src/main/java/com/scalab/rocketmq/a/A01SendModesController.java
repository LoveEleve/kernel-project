package com.scalab.rocketmq.a;

import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A01 — 三种发送方式：同步、异步、单向。
 *
 * <p>对应 D3 §二 发送方式对比表。</p>
 */
@RestController
@RequestMapping("/a01")
public class A01SendModesController {

    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    /** 异步发送结果收集（内存，仅演示） */
    private final Map<String, SendResult> asyncResults = new ConcurrentHashMap<>();

    /**
     * 同步发送——阻塞等 Broker 回执。
     */
    @GetMapping("/sync")
    public String syncSend() {
        if (rocketMQTemplate == null) {
            return "SKIP: RocketMQTemplate not wired (name-server configured?)";
        }
        long start = System.currentTimeMillis();
        SendResult result = rocketMQTemplate.syncSend("A01-Topic",
                MessageBuilder.withPayload("sync-hello-" + start).build());
        long rt = System.currentTimeMillis() - start;
        return String.format("SYNC OK: msgId=%s, sendStatus=%s, queue=%s, rt=%dms",
                result.getMsgId(), result.getSendStatus(), result.getMessageQueue(), rt);
    }

    /**
     * 异步发送——即刻返回，结果进回调。
     */
    @GetMapping("/async")
    public String asyncSend() {
        if (rocketMQTemplate == null) {
            return "SKIP: RocketMQTemplate not wired (name-server configured?)";
        }
        long start = System.currentTimeMillis();
        final String key = String.valueOf(start);
        rocketMQTemplate.asyncSend("A01-Topic",
                MessageBuilder.withPayload("async-hello-" + start).build(),
                new SendCallback() {
                    @Override
                    public void onSuccess(SendResult sendResult) {
                        asyncResults.put(key, sendResult);
                    }

                    @Override
                    public void onException(Throwable e) {
                        asyncResults.put(key, null);
                    }
                }, rocketMQTemplate.getProducer().getSendMsgTimeout());
        return String.format("ASYNC SENT (结果异步): key=%s, 稍后 /a01/async-result?key=%s", key, key);
    }

    /**
     * 查异步发送结果。
     */
    @GetMapping("/async-result")
    public String asyncResult(String key) {
        if (key == null) {
            return "usage: /a01/async-result?key=<from-async-response>";
        }
        SendResult r = asyncResults.get(key);
        if (r == null) {
            return "NOT YET (or failed): key=" + key;
        }
        return String.format("ASYNC RESULT: msgId=%s, sendStatus=%s, queue=%s",
                r.getMsgId(), r.getSendStatus(), r.getMessageQueue());
    }

    /**
     * 单向发送——不关心结果。
     */
    @GetMapping("/oneway")
    public String onewaySend() {
        if (rocketMQTemplate == null) {
            return "SKIP: RocketMQTemplate not wired (name-server configured?)";
        }
        rocketMQTemplate.sendOneWay("A01-Topic",
                MessageBuilder.withPayload("oneway-hello-" + System.currentTimeMillis()).build());
        return "ONEWAY SENT (no result)";
    }
}
