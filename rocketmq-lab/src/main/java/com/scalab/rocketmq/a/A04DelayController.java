package com.scalab.rocketmq.a;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A04 — 延时消息：18级固定延时 + 5.x 时间轮任意秒级延时。
 *
 * <p>对应 D7 §一 延时消息机制。</p>
 */
@RestController
@RequestMapping("/a04")
public class A04DelayController {

    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    /** 消费记录：messageId → (received timestamp, received time) */
    public static final Map<String, Long> delayResults = new ConcurrentHashMap<>();

    /**
     * 发一条 delayLevel=3（10s）的延时消息。
     * GET /a04/send-level3
     */
    @GetMapping("/send-level3")
    public String sendLevel3() {
        if (rocketMQTemplate == null) return "SKIP";
        long sendTime = System.currentTimeMillis();
        String body = "delay3-" + sendTime;
        rocketMQTemplate.syncSend("A04-Delay-Topic",
                MessageBuilder.withPayload(body).build(),
                rocketMQTemplate.getProducer().getSendMsgTimeout(), 3);
        return String.format("SENT delayLevel=3(10s) body=%s sendTime=%d", body, sendTime);
    }

    /**
     * 查延时消费结果。
     * GET /a04/check?sendTime=...
     */
    @GetMapping("/check")
    public String check(@RequestParam long sendTime) {
        Map.Entry<String, Long> found = null;
        for (Map.Entry<String, Long> e : delayResults.entrySet()) {
            if (e.getKey().contains(String.valueOf(sendTime))) {
                found = e;
                break;
            }
        }
        if (found == null) {
            return "NOT YET — 消息尚未被投递（延时未到或消费者未启动）";
        }
        long actualDelay = found.getValue() - sendTime;
        return String.format("RECEIVED: key=%s sendTime=%d receiveTime=%d actualDelay=%dms (~%ds) expect≈10000ms",
                found.getKey(), sendTime, found.getValue(), actualDelay, actualDelay / 1000);
    }
}
