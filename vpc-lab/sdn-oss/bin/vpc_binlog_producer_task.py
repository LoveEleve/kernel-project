#!/usr/bin/env python3
# 【复现】bin/vpc_binlog_producer_task.py —— 对应真实 sdn_oss/bin/vpc_binlog_producer_task.py
# 真实形态（VPC控制面.md §2.7 + test-good/zk-learning-path.md）：独立进程（start_...sh 拉起）；
#   worker_count=20 生产者 + 1 个 **CRemedialThread**（60s 补救线程）；batch_size=100；lock_timeout=60s。
# 本轮 review 修正（对齐材料）：
#   - batch 100（原 500 错）；失败语义 = **重置 0 + retryCount+1 + lastError**（原"留在 1"错）；
#   - 同 VPC 保序 = 按 vpcId 分组 + **SELECT ... FOR UPDATE 行锁**（材料 §2.8 定稿方案）；
#   - topic = vpc5.service.{vpcId % 16}（16 分区）、key=vpcId；Mediator 周期 30s；死信告警。
# 核心不变量：① at-least-once（去重在下游，幂等键 transId/epoch/binlogVersion）；
#   ② 同 VPC 串行（行锁）；③ 崩溃安全（0/2 是稳定态，1 是暂态→CRemedialThread 兜底）。
# 开源对照：Outbox 轮询发布；对照 Debezium 服务端式 outbox（本系统为自研 Python 版）。
import os
import sys
import time
import threading
from itertools import groupby

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from common.log import LOG
from common import tvpc_db_client as db
from common import tvpc_transaction_db_client as tx_db
from common.tvpc_alarm import alarm
from common.kafkab_proxy import KafkaBProxy

WORKER_COUNT = 20           # 材料口径：20 个生产者
BATCH_SIZE = 100            # 每次扫描 100 条
LOCK_TIMEOUT = 60           # sendState=1 超时重试（秒）
PARTITION_COUNT = 16        # topic 分区数：vpc5.service.{vpcId % 16}
IDLE_SLEEP_S = 1
MEDIATOR_INTERVAL_S = 30    # CRemedialThread 巡检周期

kafka = KafkaBProxy(brokers=["kafka1:9092"], sync_topic="tvpc.sync", async_topic="tvpc.binlog")


# ---------------- 生产者主循环 ----------------

def scan_and_send(worker_id):
    while True:
        try:
            rows = db.query_list(
                "SELECT * FROM t_service_trans WHERE sendState = %d ORDER BY transId LIMIT %d"
                % (tx_db.STATE_PENDING, BATCH_SIZE))
            if not rows:
                time.sleep(IDLE_SLEEP_S)  # 无待发送记录，等 1 秒
                continue
            # 按 vpcId 分组，同 VPC 的记录串行发送（真实 DDL 为 camelCase：vpcId/transId/binlogData）
            for vpc_id, group in groupby(rows, key=lambda r: r["vpcId"]):
                send_group(vpc_id, list(group))
        except Exception as e:
            LOG.error("[worker%d] producer error: %s", worker_id, e)
            time.sleep(5)


def send_group(vpc_id, rows):
    """同 VPC 保序发送（材料 §2.8 定稿版：事务 + FOR UPDATE 行锁）。
    在事务内逐条：CAS 认领(0→1) → 发 Kafka → 标记(→2)；
    行锁把"上一条成功后才能看到下一条"钉死在存储层——同 VPC 永不失序。"""
    with db.transaction() as conn:
        locked = tx_db.lock_vpc_rows(conn, vpc_id)   # SELECT ... FOR UPDATE
        for row in locked:
            if not tx_db.claim(conn, row["transId"]):
                continue  # 已被其它 worker 抢走
            try:
                kafka.send_sync(
                    topic="vpc5.service.%d" % (row["vpcId"] % PARTITION_COUNT),  # 16 分区
                    key=str(row["vpcId"]),        # 同 VPC 同分区（Kafka 内也保序）
                    value=row["binlogData"],      # 含幂等键 transId/epoch/binlogVersion
                    timeout_s=3,
                )
                tx_db.mark_sent(conn, row["transId"])       # 2
            except Exception as e:
                # 失败：重置 0 + retryCount+1 + lastError（保序纪律：不跳过失败条继续发后面的）
                tx_db.reset_with_retry(conn, row["transId"], str(e))
                break


# ---------------- CRemedialThread：只修"崩溃残留" ----------------

def remedial_thread():
    """60s 补救线程（真实类名 CRemedialThread）。
    与"发送失败重置"分工不同：它只处理 sendState=1 且 updateTime 落后 60s+ 的记录——
    即"生产者进程中途死了，没人来重置"的场景。周期 30s 巡检。"""
    while True:
        try:
            with db.transaction() as conn:
                tx_db.reset_stuck(conn, older_than_seconds=LOCK_TIMEOUT)
        except Exception as e:
            LOG.error("[CRemedialThread] reset failed: %s", e)
        time.sleep(MEDIATOR_INTERVAL_S)


# ---------------- 死信告警 ----------------

def dead_letter_loop():
    while True:
        with db.transaction() as conn:
            dead = tx_db.list_dead_letters(conn)
        for row in dead:
            alarm.send("Binlog dead letter", detail={"transId": row["transId"], "retry": row["retryCount"]})
        time.sleep(60)


def main():
    LOG.info("[binlog-producer] starting: %d producers + 1 CRemedialThread", WORKER_COUNT)
    threading.Thread(target=remedial_thread, name="CRemedialThread", daemon=True).start()
    threading.Thread(target=dead_letter_loop, name="dead-letter", daemon=True).start()
    for i in range(WORKER_COUNT):
        threading.Thread(target=scan_and_send, args=(i,), name="producer-%d" % i, daemon=True).start()
    while True:
        time.sleep(3600)


if __name__ == "__main__":
    main()
