package com.scalab.es;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * es-lab：Elasticsearch 8.12.2 学习模块（本地单节点 ES，对齐 xhs-project 8.12.2）。
 *
 * <p>实验：
 * <ul>
 *   <li>A01 索引与 Mapping（倒排/分词基础）</li>
 *   <li>A02 文档 CRUD + Bulk</li>
 *   <li>A03 查询 DSL（match/term/bool/分页排序）</li>
 *   <li>A04 ExternalGte 版本控制（xhs 索引同步幂等的核心）</li>
 *   <li>A05 索引同步模式（DB 变更 → 乱序到达 → 旧版本被拒）</li>
 * </ul>
 */
@SpringBootApplication
public class EsLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(EsLabApplication.class, args);
    }
}
