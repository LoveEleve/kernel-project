package com.scalab.rocketmq.a;

import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;

/**
 * A06 — 事务消息验证（半消息 + 本地事务 + 回查）。
 */
@RestController
@RequestMapping("/a06")
public class A06TransactionController {

    private final TransactionMQProducer txProducer;

    public A06TransactionController(@Qualifier("a06TransactionProducer") TransactionMQProducer txProducer) {
        this.txProducer = txProducer;
    }

    @PostConstruct
    public void start() throws Exception {
        txProducer.start();
    }

    @PreDestroy
    public void shutdown() {
        txProducer.shutdown();
    }

    @GetMapping("/send")
    public String send(@RequestParam(defaultValue = "tx-test") String body) {
        try {
            Message msg = new Message("A06-Tx-Topic", body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            TransactionSendResult result = txProducer.sendMessageInTransaction(msg, "ctx-" + System.currentTimeMillis());
            return String.format("TX SENT: msgId=%s, sendStatus=%s, localState=%s",
                    result.getMsgId(), result.getSendStatus(), result.getLocalTransactionState());
        } catch (Exception e) {
            return "TX FAIL: " + e.getMessage();
        }
    }

    @GetMapping("/status")
    public Map<String, String> status() {
        return A06TransactionConfig.txLog;
    }
}
