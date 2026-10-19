package com.scalab.seata.a;

import io.seata.spring.annotation.GlobalTransactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * A01 — AT 模式转账验证：account-A → account-B，@GlobalTransactional 保证两个账户的变动一致。
 */
@RestController
@RequestMapping("/a01")
public class A01TransferController {

    private final JdbcTemplate jdbcTemplate;

    public A01TransferController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/transfer")
    @GlobalTransactional(timeoutMills = 30000, rollbackFor = Exception.class)
    public String transfer(@RequestParam(defaultValue = "100") BigDecimal amount) {
        jdbcTemplate.update("UPDATE seata_account SET balance = balance - ? WHERE id = 1", amount);
        jdbcTemplate.update("UPDATE seata_account SET balance = balance + ? WHERE id = 2", amount);
        BigDecimal a = jdbcTemplate.queryForObject(
                "SELECT balance FROM seata_account WHERE id = 1", BigDecimal.class);
        BigDecimal b = jdbcTemplate.queryForObject(
                "SELECT balance FROM seata_account WHERE id = 2", BigDecimal.class);
        return String.format("TRANSFER OK: A=%s B=%s", a, b);
    }

    @GetMapping("/transfer-rollback")
    @GlobalTransactional(timeoutMills = 30000, rollbackFor = Exception.class)
    public String transferRollback(@RequestParam(defaultValue = "100") BigDecimal amount) {
        jdbcTemplate.update("UPDATE seata_account SET balance = balance - ? WHERE id = 1", amount);
        throw new RuntimeException("模拟失败-触发全局事务回滚");
    }

    @GetMapping("/balances")
    public String balances() {
        BigDecimal a = jdbcTemplate.queryForObject(
                "SELECT balance FROM seata_account WHERE id = 1", BigDecimal.class);
        BigDecimal b = jdbcTemplate.queryForObject(
                "SELECT balance FROM seata_account WHERE id = 2", BigDecimal.class);
        return String.format("A=%s B=%s total=%s", a, b, a.add(b));
    }

    @GetMapping("/reset")
    public String reset() {
        jdbcTemplate.update("UPDATE seata_account SET balance = 1000.00 WHERE id IN (1,2)");
        return "RESET: A=1000 B=1000";
    }
}
