package com.scalab.consistency.a;

import com.scalab.consistency.core.FakeMqBroker;
import com.scalab.consistency.core.RetryBackoffUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A02 Outbox 模式——业务表与本地消息表同事务 + 补发（指数退避）+ 死信终态。
 *
 * <p>实验（核心场景：Broker 挂掉消息不丢）：
 * <ol>
 *   <li>{@code /a02/inject-failure?enabled=true} 注入 Broker 故障</li>
 *   <li>{@code /a02/place-order?orderId=o1&amount=5} 下单——DB 事务成功，投递失败 → outbox 留待补发</li>
 *   <li>{@code /a02/inject-failure?enabled=false} 恢复 Broker</li>
 *   <li>{@code /a02/flush-outbox} 补发——消息到达 Broker（"消息不丢"）</li>
 *   <li>{@code /a02/outbox-status} + {@code /a02/broker} 看状态与投递结果</li>
 * </ol>
 */
@RestController
@RequestMapping("/a02")
public class A02OutboxController {

    private static final String TOPIC = "ORDER_CREATED_TOPIC";
    private static final int MAX_RETRIES = 5;
    private static final long BASE_SECONDS = 30;
    private static final long MAX_SECONDS = 480;
    private static final double JITTER = 0.2;

    private final JdbcTemplate jdbc;
    private final FakeMqBroker broker;

    public A02OutboxController(JdbcTemplate jdbc, FakeMqBroker broker) {
        this.jdbc = jdbc;
        this.broker = broker;
    }

    /** 下单：扣余额 + 写 outbox（同一本地事务）→ 立即尝试投递（失败则留给补发）。 */
    @PostMapping("/place-order")
    @Transactional
    public Map<String, Object> placeOrder(@RequestParam String orderId,
                                          @RequestParam(defaultValue = "5") long amount) {
        Map<String, Object> r = new LinkedHashMap<>();
        jdbc.update("UPDATE account SET balance = balance - ?, version = version + 1 WHERE id = 1", amount);
        jdbc.update("INSERT INTO outbox(biz_type, biz_id, payload, status, retry_count, next_retry_time) "
                        + "VALUES ('order-created', ?, ?, 0, 0, CURRENT_TIMESTAMP)",
                orderId, "{\"orderId\":\"" + orderId + "\",\"amount\":" + amount + "}");
        r.put("dbTx", "余额扣减 + outbox 写入（同一事务）✓");

        // 事务内尝试即时投递（失败不抛——留给补发；xhs-project 中 syncSend 超时同理）
        try {
            broker.send(TOPIC, "order-created:" + orderId);
            jdbc.update("UPDATE outbox SET status = 1, updated_at = CURRENT_TIMESTAMP WHERE biz_id = ?", orderId);
            r.put("immediateSend", "投递成功（status=1）");
        } catch (Exception e) {
            r.put("immediateSend", "投递失败（" + e.getMessage() + "）→ 留在 outbox 等待补发");
        }
        r.put("balance", balance());
        return r;
    }

    @PostMapping("/inject-failure")
    public Map<String, Object> injectFailure(@RequestParam boolean enabled) {
        broker.injectFailure(enabled);
        return Map.of("failureInjected", enabled);
    }

    /**
     * 补发：扫描 status IN (0,2) 且到期的记录 → 投递 → 成功置 1；失败重试计数 + 退避；超限置 3（死信）。
     *
     * @param force true = 忽略 next_retry_time 到期检查（实验用，免等退避窗口）
     */
    @PostMapping("/flush-outbox")
    public Map<String, Object> flushOutbox(@RequestParam(defaultValue = "false") boolean force) {
        String sql = "SELECT * FROM outbox WHERE status IN (0, 2) "
                + (force ? "" : "AND next_retry_time <= CURRENT_TIMESTAMP ")
                + "ORDER BY next_retry_time LIMIT 50";
        List<Map<String, Object>> rows = jdbc.queryForList(sql);
        List<String> detail = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            int retry = ((Number) row.get("retry_count")).intValue();
            String payload = (String) row.get("payload");
            try {
                broker.send(TOPIC, payload);
                jdbc.update("UPDATE outbox SET status = 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
                detail.add("outbox#" + id + " 补发成功（第 " + retry + " 次重试后）");
            } catch (Exception e) {
                int next = retry + 1;
                if (next >= MAX_RETRIES) {
                    jdbc.update("UPDATE outbox SET status = 3, retry_count = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                            next, id);
                    detail.add("outbox#" + id + " 重试耗尽 → 死信（status=3）");
                } else {
                    long delay = RetryBackoffUtils.exponentialSeconds(next, BASE_SECONDS, MAX_SECONDS, JITTER);
                    jdbc.update("UPDATE outbox SET status = 2, retry_count = ?, "
                                    + "next_retry_time = DATEADD('SECOND', ?, CURRENT_TIMESTAMP), updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                            next, delay, id);
                    detail.add("outbox#" + id + " 投递失败 → 第 " + next + " 次重试将于约 " + delay + "s 后");
                }
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("processed", rows.size());
        r.put("detail", detail);
        r.put("brokerFailureInjected", broker.isFailureInjected());
        return r;
    }

    @GetMapping("/outbox-status")
    public List<Map<String, Object>> outboxStatus() {
        return jdbc.queryForList("SELECT id, biz_id, status, retry_count, next_retry_time FROM outbox ORDER BY id");
    }

    @GetMapping("/broker")
    public Map<String, Object> brokerState() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("received", broker.receivedMessages());
        r.put("attemptLog", broker.attemptLog());
        return r;
    }

    @DeleteMapping("/reset")
    public Map<String, Object> reset() {
        jdbc.update("DELETE FROM outbox");
        jdbc.update("UPDATE account SET balance = 1000, version = 0 WHERE id = 1");
        broker.reset();
        return Map.of("result", "已重置（outbox 清空 / broker 清空 / balance=1000）");
    }

    private long balance() {
        return jdbc.queryForObject("SELECT balance FROM account WHERE id = 1", Long.class);
    }
}
