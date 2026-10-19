package com.scalab.consistency.a;

import com.scalab.consistency.core.IdempotentHelper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A01 幂等模式——Redis SETNX 去重 + removeMark 允许重试 + DB 唯一键兜底。
 *
 * <p>实验：
 * <ol>
 *   <li>{@code /a01/consume?msgId=m1} 两次——第二次被幂等拦截（余额只加一次）</li>
 *   <li>{@code /a01/consume?msgId=m2&failFirst=true}——业务失败 → removeMark → 再次调用可成功</li>
 *   <li>{@code /a01/status} 看余额 / Redis 标记数 / DB 去重记录数</li>
 * </ol>
 */
@RestController
@RequestMapping("/a01")
public class A01IdempotentController {

    private static final String BIZ_TYPE = "account-topic";

    private final IdempotentHelper idempotentHelper;
    private final JdbcTemplate jdbc;

    public A01IdempotentController(IdempotentHelper idempotentHelper, JdbcTemplate jdbc) {
        this.idempotentHelper = idempotentHelper;
        this.jdbc = jdbc;
    }

    @PostMapping("/consume")
    public Map<String, Object> consume(@RequestParam String msgId,
                                       @RequestParam(defaultValue = "10") long amount,
                                       @RequestParam(defaultValue = "false") boolean failFirst) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("msgId", msgId);

        boolean first = idempotentHelper.isFirstProcess(BIZ_TYPE, msgId, 86400);
        if (!first) {
            r.put("result", "重复消息已忽略（Redis SETNX 幂等拦截）");
            r.put("balance", balance());
            return r;
        }

        if (failFirst) {
            idempotentHelper.removeMark(BIZ_TYPE, msgId);
            r.put("result", "业务失败 → removeMark（删除标记，允许 MQ 重试）");
            r.put("balance", balance());
            return r;
        }

        // 第二道防线：DB 唯一键（Redis 降级放行时的兜底）
        try {
            jdbc.update("INSERT INTO consume_record(biz_type, biz_id) VALUES (?, ?)", BIZ_TYPE, msgId);
        } catch (DuplicateKeyException e) {
            r.put("result", "DB 唯一键拦截（consume_record 冲突——第二道防线生效）");
            r.put("balance", balance());
            return r;
        }
        jdbc.update("UPDATE account SET balance = balance + ?, version = version + 1 WHERE id = 1", amount);
        r.put("result", "首次处理成功（余额 +" + amount + "）");
        r.put("balance", balance());
        return r;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("balance", balance());
        r.put("redisMarks", idempotentHelper.countMarks());
        r.put("dbRecords", jdbc.queryForObject("SELECT COUNT(*) FROM consume_record", Long.class));
        return r;
    }

    @DeleteMapping("/reset")
    public Map<String, Object> reset() {
        jdbc.update("DELETE FROM consume_record");
        jdbc.update("UPDATE account SET balance = 1000, version = 0 WHERE id = 1");
        return Map.of("result", "已重置（balance=1000；Redis 标记保留——可手动 redis-cli DEL）");
    }

    private long balance() {
        return jdbc.queryForObject("SELECT balance FROM account WHERE id = 1", Long.class);
    }
}
