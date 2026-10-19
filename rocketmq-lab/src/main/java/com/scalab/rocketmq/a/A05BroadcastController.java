package com.scalab.rocketmq.a;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * A05 — 广播消费验证。
 *
 * <p>每个实例收到全量消息（vs 集群的队列分摊），offset 存本地文件，失败直接丢弃不重试。</p>
 * <p>对应 D7 §三 广播消息语义 + D5 §四 广播失败丢弃。</p>
 */
@RestController
@RequestMapping("/a05")
public class A05BroadcastController {

    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    /** 广播消费记录 */
    public static final List<String> broadcastLog = new CopyOnWriteArrayList<>();
    /** 集群消费记录（对比用） */
    public static final Map<Integer, List<String>> clusterLog = new ConcurrentHashMap<>();

    /**
     * 发 5 条到广播 Topic　— 单实例场景下全部收到。
     */
    @GetMapping("/send")
    public String send() {
        if (rocketMQTemplate == null) return "SKIP";
        for (int i = 1; i <= 5; i++) {
            rocketMQTemplate.syncSend("A05-Broadcast-Topic",
                    MessageBuilder.withPayload("broadcast-" + i).build());
        }
        return "SENT 5 messages to A05-Broadcast-Topic";
    }

    @GetMapping("/log")
    public List<String> log() {
        return broadcastLog;
    }
}
