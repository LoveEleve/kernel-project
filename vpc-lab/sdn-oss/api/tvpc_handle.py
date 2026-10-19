# 【复现】api/tvpc_handle.py —— 对应真实 sdn_oss/api/tvpc_handle.py（393KB，全服务最核心框架文件）
# 真实职责（docs-by-topic 24 §3.1.1 + VPC控制面.md §2.2/2.3/2.6）：
#   核心 Handle 基类 CTvpcThread = Front Controller + **Saga 补偿事务引擎**：
#   - "写 MySQL + 写 ZK 配置树"的双写流程、事务、灰度白名单、VPC5 装饰器挂载点；
#   - 定义 /TVPC 下全部 ZK 根路径常量。
# 证据等级（本轮 review 升级）：
#   - Saga 正/逆序回滚 + add_step(forward, backward, skip_on_codes) 三参数 = 实证（VPC控制面 §2.3 伪代码保留全部细节）；
#   - 按对象分批并发（split_num=20 / max_concurrency=20 / BoundedSemaphore / SagaWorker）= 实证（同上）；
#   - 回滚失败 → Redis 缓存（24h）+ 告警 + 对外暴露 Revert.TVPC.* 重试接口 = 实证（§2.6）；
#   - Saga 步骤与 Outbox 同一 MySQL 事务（源唯一性）= 实证（§2.7）。
# 开源对照（R3 补证）：
#   - Saga 原论文：本地 papers/03-Saga-SIGMOD1987.pdf（LLT：每步注册补偿、失败逆序回滚）；
#   - OpenStack TaskFlow 的工程化：RevertAll 策略（refs/taskflow/taskflow/engines/action_engine/completer.py:72）
#     ——失败→全量逆序回滚；**回滚自身失败 → REVERT_FAILURE 状态**（refs/.../actions/task.py:153），
#     需要人工/外部介入——对照本文件"回滚失败 → Redis 缓存 + Revert.TVPC.* 重试接口"的同题解法；
#   - 并发模型对照 Java ExecutorService + Semaphore（材料 §7 做过 TF 对比）。
import json
import threading
import time

try:
    from queue import Queue
except ImportError:  # Python 2（真实运行环境）
    from Queue import Queue

from common.log import LOG
from common import tvpc_db_client as db
from common import tvpc_transaction_db_client as tx_db
from common.tvpc_alarm import alarm
from common import tvpc_redis

# /TVPC 下 ZK 根路径常量（真实口径：全部 ZK 根路径在此定义，全服务引用）。
ZK_ROOT = {
    "vpc":    "/TVPC/vpc",
    "rtb":    "/TVPC/rtb",
    "subnet": "/TVPC/subnet",
    "eni":    "/TVPC/eni",
    "sg":     "/TVPC/sg",
}

# 灰度白名单：新链路（如 VPC5 适配）按 uin/vpcId 灰度放量的开关（真实机制）。
GRAY_WHITELIST = set()


class StepError(Exception):
    """步骤失败（带错误码：供 skip_on_codes 判断）。"""

    def __init__(self, code, msg=""):
        super(StepError, self).__init__("%s: %s" % (code, msg))
        self.code = code


class SkipError(Exception):
    """"记录已不存在/无需处理"——不算失败（如 backward 删一行 rows_affected==0）。"""

    def __init__(self, code="NOT_EXISTS"):
        super(SkipError, self).__init__(code)
        self.code = code


class SagaTransactionFailed(Exception):
    def __init__(self, failed_items):
        super(SagaTransactionFailed, self).__init__("saga failed: %s" % failed_items)
        self.failed_items = failed_items


def chunks(seq, n):
    for i in range(0, len(seq), n):
        yield seq[i:i + n]


class CTvpcThread(object):
    """Saga 基类（伪代码汇编中亦称 SagaHandle）。子类在 __init__ 里用 add_step 编排步骤链。"""

    _vpc5_mixin = None  # VPC5 装饰器挂载点（见 api/Vpc5/decorator.py）

    def __init__(self, ctx=None):
        self.steps = []              # [{'forward','backward','skip_codes','name'}]，有序
        self.split_num = 20          # 每批处理对象数（材料口径）
        self.max_concurrency = 20    # 最大并发线程数（材料口径）
        self.ctx = ctx

    # ---------------- 编排 ----------------

    def add_step(self, forward_fn, backward_fn=None, skip_on_codes=None):
        """登记一步。backward 可为 None（不可回滚步骤必须放最后）；
        skip_on_codes：forward 抛出的错误码落在表内 → **跳过后续步骤而非回滚**。"""
        self.steps.append({
            "forward": forward_fn,
            "backward": backward_fn,
            "skip_codes": skip_on_codes or [],
            "name": getattr(forward_fn, "__name__", str(forward_fn)),
        })

    # ---------------- 执行（按对象批量 + 并发） ----------------

    def execute(self, items):
        if self._vpc5_mixin is not None:
            self._vpc5_mixin.prepare(self.ctx)  # VPC5 双模型：灰度命中时给 ctx 挂新对象模型适配器
        for batch in chunks(items, self.split_num):
            self._execute_batch(batch)

    def _execute_batch(self, batch):
        semaphore = threading.BoundedSemaphore(self.max_concurrency)
        workers = []
        for item in batch:
            semaphore.acquire()
            w = SagaWorker(item, self.steps, semaphore, self.ctx)
            workers.append(w)
            w.start()
        failed = []
        for w in workers:
            w.join()
            if w.state == "failed":
                failed.append(w)
        # 任何一个失败 → 本批全部回滚（已完成的 + 失败的；skipped 不回滚）
        if failed:
            for w in workers:
                if w.state in ("completed", "failed"):
                    w.rollback()
            raise SagaTransactionFailed([w.item for w in failed])

    # ---------------- 双写 helper（Saga+Outbox 同一事务） ----------------

    def write_db_and_enqueue(self, conn, fn, item, outbox_actions, ctx):
        """Saga 的一个步骤内部：业务表写 + Outbox 登记，**同一 MySQL 事务**。
        提交成功 = 变更已生效；ZK/Kafka 是提交后的派生投递（producer/补偿任务负责）。"""
        fn(conn)
        for action in outbox_actions:
            tx_db.insert_trans(
                conn,
                vpc_id=item.get("vpcId"),
                svc_id=item.get("svcId"),
                epoch=item.get("epoch", 0),
                action=item.get("action", "UPDATE"),
                action_type=action,        # zk_put / kafka_binlog
                payload=serialize_binlog(item),
                send_state=tx_db.STATE_PENDING,
                create_time=int(time.time()),
            )


class SagaWorker(threading.Thread):
    """单个对象的步骤链执行器。状态：completed / failed / skipped。"""

    def __init__(self, item, steps, semaphore, ctx):
        super(SagaWorker, self).__init__()
        self.item = item
        self.steps = steps
        self.semaphore = semaphore
        self.ctx = ctx
        self.executed = []   # 已成功步骤的下标
        self.state = "completed"
        self.rollback_log = []

    def run(self):
        try:
            for i, step in enumerate(self.steps):
                try:
                    LOG.info("[step] %s begin vpc=%s", step["name"], self.item.get("vpcId"))
                    step["forward"](self.item, self.ctx)
                    self.executed.append(i)
                except StepError as e:
                    if e.code in step["skip_codes"]:
                        # 跳过后续步骤，不回滚（材料语义）。
                        LOG.warn("[step] %s skipped(code=%s), remaining steps skipped", step["name"], e.code)
                        self.state = "skipped"
                        return
                    self.state = "failed"
                    return
                except Exception:
                    self.state = "failed"
                    return
        finally:
            self.semaphore.release()

    def rollback(self):
        """逆序回滚。backward 抛 SkipError（如 NOT_EXISTS）视为正常；抛其它异常 →
        Redis 缓存回滚上下文（24h）+ 告警，留给 Revert.TVPC.* 接口重试。"""
        for i in reversed(self.executed):
            step = self.steps[i]
            if not step["backward"]:
                LOG.error("[rollback] %s NOT compensatable — 需人工/对账介入", step["name"])
                continue
            try:
                LOG.warn("[rollback] %s", step["name"])
                step["backward"](self.item, self.ctx)
                self.rollback_log.append((step["name"], "ok"))
            except SkipError as e:
                self.rollback_log.append((step["name"], "skipped(%s)" % e.code))
            except Exception as e:
                # 回滚失败不抛：缓存上下文到 Redis（24h）→ 供自动/人工重试（Revert 接口）+ 告警。
                cache_key = "rollback:%s:%s" % (self.item.get("vpcId"), i)
                tvpc_redis.set(cache_key, json.dumps({
                    "item": self.item, "step": step["name"], "error": str(e), "timestamp": time.time(),
                }), ex=86400)
                alarm.send("Step rollback failed: %s" % step["name"], detail={"error": str(e)})
                self.rollback_log.append((step["name"], "failed: %s" % e))


def serialize_binlog(item):
    """binlog 消息体：含幂等键 transId/epoch/binlogVersion（材料口径，消费者据此去重）。"""
    return json.dumps({
        "transId": item.get("transId"),
        "epoch": item.get("epoch", 0),                # VPC 代际：删除重建后递增
        "binlogVersion": item.get("binlogVersion", 0),
        "vpcId": item.get("vpcId"),
        "action": item.get("action", "UPDATE"),
        "data": item,
    })
