-- dist-consistency-lab 表结构（H2 内存库，模式对齐 xhs-project）

-- 业务表：账户余额（幂等扣减 + 对账 demo 的 DB 侧）
CREATE TABLE IF NOT EXISTS account (
    id      BIGINT PRIMARY KEY,
    balance BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

-- 幂等-去重表（Redis 之外的第二道防线：DB 唯一键）
CREATE TABLE IF NOT EXISTS consume_record (
    biz_type   VARCHAR(64)  NOT NULL,
    biz_id     VARCHAR(128) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (biz_type, biz_id)
);

-- Outbox（本地消息表）：业务表与它同事务写入
CREATE TABLE IF NOT EXISTS outbox (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    biz_type        VARCHAR(64)   NOT NULL,
    biz_id          VARCHAR(128)  NOT NULL,
    payload         VARCHAR(1024) NOT NULL,
    status          TINYINT       NOT NULL DEFAULT 0,  -- 0=待发送 1=已发送 2=失败 3=死信
    retry_count     INT           NOT NULL DEFAULT 0,
    next_retry_time TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 初始数据
MERGE INTO account (id, balance, version) KEY(id) VALUES (1, 1000, 0);
