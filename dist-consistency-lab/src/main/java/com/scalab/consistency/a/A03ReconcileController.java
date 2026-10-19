package com.scalab.consistency.a;

import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * A03 对账模式——Redis（实时权威源）↔ H2（异步落库）对比 + 差异修复。
 *
 * <p>场景对齐 xhs-project 库存对账：Redis 是实时扣减的权威数据源，
 * DB 是异步落库的副本——对账以 Redis 为准修复 DB。
 *
 * <p>实验：
 * <ol>
 *   <li>{@code /a03/op?amount=7&skipDb=true} 扣减（Redis 成功、DB 落库丢失）→ 制造差异</li>
 *   <li>{@code /a03/status} 看 Redis vs DB 差异</li>
 *   <li>{@code /a03/reconcile} 对账修复（以 Redis 为准）</li>
 *   <li>{@code /a03/status} 差异归零</li>
 * </ol>
 */
@RestController
@RequestMapping("/a03")
public class A03ReconcileController {

    private static final String KEY = "account:1:balance";
    private static final long INIT = 1000;

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    public A03ReconcileController(StringRedisTemplate redis, JdbcTemplate jdbc) {
        this.redis = redis;
        this.jdbc = jdbc;
    }

    /** 启动时把 Redis 初始化为 DB 值（幂等：已存在则不覆盖）。 */
    @PostConstruct
    public void seed() {
        if (Boolean.FALSE.equals(redis.hasKey(KEY))) {
            redis.opsForValue().set(KEY, String.valueOf(dbBalance()));
        }
    }

    /** 实时扣减：Redis 权威 +（可选）异步落库。skipDb=true 模拟落库丢失。 */
    @PostMapping("/op")
    public Map<String, Object> op(@RequestParam long amount,
                                  @RequestParam(defaultValue = "false") boolean skipDb) {
        Long after = redis.opsForValue().decrement(KEY, amount);
        if (!skipDb) {
            jdbc.update("UPDATE account SET balance = balance - ?, version = version + 1 WHERE id = 1", amount);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("redisAfter", after);
        r.put("dbWritten", !skipDb);
        r.put("note", skipDb ? "DB 落库被跳过（模拟异步落库丢失/延迟）——差异已制造" : "Redis + DB 双写一致");
        return r;
    }

    /** 直接改 Redis（模拟 Redis 侧漂移）。 */
    @PostMapping("/drift")
    public Map<String, Object> drift(@RequestParam long amount) {
        Long after = redis.opsForValue().increment(KEY, amount);
        return Map.of("redisAfter", after, "note", "Redis 侧漂移（DB 未变）");
    }

    /** 对账修复：以 Redis 为准修复 DB。 */
    @PostMapping("/reconcile")
    public Map<String, Object> reconcile() {
        long redisVal = redisBalance();
        long dbVal = dbBalance();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("redis(权威源)", redisVal);
        r.put("db(修复前)", dbVal);
        if (redisVal == dbVal) {
            r.put("result", "无差异，无需修复");
            return r;
        }
        jdbc.update("UPDATE account SET balance = ?, version = version + 1 WHERE id = 1", redisVal);
        r.put("db(修复后)", dbBalance());
        r.put("result", "已按权威源（Redis）修复 DB（差异 " + (dbVal - redisVal) + "）");
        return r;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        long redisVal = redisBalance();
        long dbVal = dbBalance();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("redis", redisVal);
        r.put("db", dbVal);
        r.put("diff(db-redis)", dbVal - redisVal);
        r.put("consistent", redisVal == dbVal);
        return r;
    }

    @DeleteMapping("/reset")
    public Map<String, Object> reset() {
        redis.opsForValue().set(KEY, String.valueOf(INIT));
        jdbc.update("UPDATE account SET balance = ?, version = 0 WHERE id = 1", INIT);
        return Map.of("result", "已重置（Redis=DB=" + INIT + "）");
    }

    private long redisBalance() {
        String v = redis.opsForValue().get(KEY);
        return v == null ? -1 : Long.parseLong(v);
    }

    private long dbBalance() {
        return jdbc.queryForObject("SELECT balance FROM account WHERE id = 1", Long.class);
    }
}
