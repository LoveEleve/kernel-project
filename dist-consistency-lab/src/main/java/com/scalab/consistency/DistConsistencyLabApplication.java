package com.scalab.consistency;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * dist-consistency-lab：分布式一致性模式最小对照。
 *
 * <p>四个模式（对齐 xhs-project 真实实现）：
 * <ul>
 *   <li>A01 幂等——Redis SETNX + removeMark + DB 唯一键兜底</li>
 *   <li>A02 Outbox——业务库同事务写消息表 + 补发（指数退避）</li>
 *   <li>A03 对账——Redis（权威源）↔ H2 对比 + 修复</li>
 *   <li>A04 补偿重试——指数退避 + 抖动 + 死信终态</li>
 * </ul>
 */
@SpringBootApplication
public class DistConsistencyLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(DistConsistencyLabApplication.class, args);
    }
}
