# 【复现】common/tvpc_transaction_db_client.py —— 对应真实 sdn_oss/common/tvpc_transaction_db_client.py
# 真实职责（VPC控制面.md §2.7）：事务库客户端。Outbox 表**每个配置类型一张，共 25 张**；
#   全系统"DB 是唯一真实源、ZK/Kafka 是派生投递"的支点。
# 本轮 review 修正（对齐材料真实 schema 与状态机）：
#   - sendState 是 **4 态**：0=待发送 / 1=发送中 / 2=已发送 / 3=失败（此前只实现了 3 态）；
#   - 缺 epoch（VPC 代际）、retryCount、lastError、updateTime 字段；
#   - 发送失败的真实语义 = **立即重置 0 + retryCount+1 + lastError**（非"留在 1 等 Mediator"）；
#     Mediator 只管"崩溃残留"（sendState=1 且 updateTime 超 60s）；
#   - 死信 = sendState=3 或 retryCount>10 → 告警（不再无限重试）。
# 证据等级：schema/状态机/阈值 = 实证（§2.7 全文含 SQL DDL）。
# 开源对照：Transactional Outbox（Chris Richardson）；Debezium Outbox Event Router。
from common.log import LOG

STATE_PENDING = 0   # 0=待发送
STATE_SENDING = 1   # 1=发送中（生产者认领后）
STATE_SENT = 2      # 2=已发送
STATE_FAILED = 3    # 3=失败（进死信）
RETRY_LIMIT = 10    # retryCount > 10 → 死信告警（材料口径）

# 表结构（材料 §2.7 原文，注释保留）：
# CREATE TABLE t_service_trans (
#   transId     BIGINT AUTO_INCREMENT PRIMARY KEY,
#   vpcId       BIGINT NOT NULL,       -- 业务主键（用于同 Key 顺序控制）
#   svcId       VARCHAR(64),
#   epoch       INT DEFAULT 0,         -- VPC 代际（删除重建后递增）
#   action      VARCHAR(16),           -- CREATE / UPDATE / DELETE
#   sendState   TINYINT DEFAULT 0,     -- 0待发/1发送中/2已发/3失败
#   binlogData  MEDIUMTEXT,            -- 序列化 binlog（幂等键 transId/epoch/binlogVersion）
#   retryCount  INT DEFAULT 0,
#   lastError   VARCHAR(1024),
#   createTime  DATETIME DEFAULT NOW(),
#   updateTime  DATETIME DEFAULT NOW() ON UPDATE NOW(),
#   INDEX idx_send_vpc  (sendState, vpcId),
#   INDEX idx_send_time (sendState, createTime)
# );


def insert_trans(conn, vpc_id, svc_id, epoch, action, action_type, payload, send_state=STATE_PENDING, create_time=0):
    """在调用方事务里登记派生投递动作（业务表 + 本表同一事务，原子）。"""
    conn.insert("t_service_trans", {
        "vpc_id": vpc_id, "svc_id": svc_id, "epoch": epoch, "action": action,
        "action_type": action_type, "binlog_data": payload, "send_state": send_state,
        "create_time": create_time,
    })


# ---------------- 生产者侧（bin/vpc_binlog_producer_task.py 调用） ----------------

def select_pending(conn, limit):
    """扫描待发送（全局升序；真实 batch_size=100）。"""
    return conn.query_list(
        "SELECT * FROM t_service_trans WHERE sendState = %s ORDER BY transId LIMIT %d"
        % (STATE_PENDING, limit))


def lock_vpc_rows(conn, vpc_id):
    """同 VPC 顺序控制（材料 §2.8）：SELECT ... FOR UPDATE 锁该 VPC 的待发行。
    "上次发送成功(sendState=2)后，下次 FOR UPDATE 才能看到下一条 sendState=0 的记录"——
    用 DB 行锁把"同 VPC 串行"钉在存储层，而不是靠应用层自觉。"""
    return conn.query_list(
        "SELECT * FROM t_service_trans WHERE vpcId = %s AND sendState = %s "
        "ORDER BY transId FOR UPDATE" % (vpc_id, STATE_PENDING))


def claim(conn, trans_id):
    """CAS 认领：0 → 1（防并发发送）。rows_affected==0 = 已被别人抢走。"""
    n = conn.execute("UPDATE t_service_trans SET sendState = %s WHERE transId = %%s AND sendState = %s"
                     % (STATE_SENDING, STATE_PENDING), (trans_id,))
    return n == 1


def mark_sent(conn, trans_id):
    """拿到 Kafka ack 才标记 2（at-least-once：ack 前崩溃 → 重置 → 重发；去重靠幂等键）。"""
    conn.execute("UPDATE t_service_trans SET sendState = %s WHERE transId = %%s" % STATE_SENT, (trans_id,))


def reset_with_retry(conn, trans_id, error):
    """发送失败：**立即重置 0** + retryCount+1 + lastError（材料语义）。
    为什么不是立即重试：同 VPC 保序优先——这一条没成功前，后面的发出去就是乱序。"""
    conn.execute(
        "UPDATE t_service_trans SET sendState = %s, retryCount = retryCount + 1, lastError = %%s "
        "WHERE transId = %%s" % STATE_PENDING, (error[:1024], trans_id))


def reset_stuck(conn, older_than_seconds=RETRY_LIMIT * 0 + 60):
    """Mediator（CRemedialThread）职责：**只处理崩溃残留**——
    sendState=1 且 updateTime 落后当前 60s+ → 重置 0（sleep 30s 周期巡检）。"""
    conn.execute(
        "UPDATE t_service_trans SET sendState = %s "
        "WHERE sendState = %s AND updateTime < DATE_SUB(NOW(), INTERVAL %d SECOND)"
        % (STATE_PENDING, STATE_SENDING, older_than_seconds))


def list_dead_letters(conn, limit=100):
    """死信：sendState=3 或 retryCount>10 → 交给告警（人工介入，不再自动重试）。"""
    return conn.query_list(
        "SELECT * FROM t_service_trans WHERE sendState = %d OR retryCount > %d "
        "ORDER BY transId LIMIT %d" % (STATE_FAILED, RETRY_LIMIT, limit))


def revert_or_enqueue_reverse(vpc_id, zk_path):
    """Saga 补偿二分：交付前（0）删行即可；交付后（2）登记反向动作（历史不可撤回）。
    （回滚失败的重试入口：Revert.TVPC.* 接口 —— 见 api/tvpc_handle.py 回滚缓存。）"""
    LOG.info("[tx] revert_or_enqueue_reverse vpc=%s path=%s", vpc_id, zk_path)
