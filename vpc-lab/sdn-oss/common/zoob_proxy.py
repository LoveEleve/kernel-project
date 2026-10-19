# 【复现】common/zoob_proxy.py —— 对应真实 sdn_oss/common/zoob_proxy.py（+ zoob_req_op.py / zoob_util.py）
# 真实职责：zoob（ZK 代理服务）请求代理——**ZK 异步写入**（VPC控制面.md:3244）。
# 真实类名（VPC控制面.md:24286-24287 实证）：
#   common/zoob_proxy.py → CZoobExchange / CZoobProxy / CZoobReqOp（经 zoob 代理访问 ZK 的请求链路）；
#   common/zoob_util.py  → CZoobOpHandle 系列（zoob 协议的操作句柄）。
# 为什么需要代理层：ZK 写是网络往返，不能让 HTTP 请求线程同步等；且 ZK 树写入要求同一 VPC 保序——
#   代理层天然是"汇聚 + 排序 + 重试"的位置。（24 清单曾标"推测"，本轮已升级为类名级实证。）
# 证据等级：代理存在性/类名/ZK 异步写入 = 实证；队列/保序/死信结构 = 由需求推导（教学等价）。
# 开源对照：写前队列（WAL 思路）；ZooKeeper 官方 recipe 无此层——"写放大治理"自研件。
import queue
import threading
import time

from common.log import LOG


class CZoobReqOp(object):
    """zoob 请求对象（对应真实 CZoobReqOp）。"""

    def __init__(self, op, path, value=None, version=0):
        self.op = op              # put / delete
        self.path = path
        self.value = value
        self.version = version    # 附版本：ZK 侧按版本比较，旧版本不覆盖新版本
        self.retry = 0


class CZoobExchange(object):
    """与 zoob 服务的连接（对应真实 CZoobExchange；此处为桩）。"""

    def send(self, vpc_id, req):
        raise NotImplementedError("connect to zoob")


class CZoobProxy(object):
    """ZK 写入代理：入队即返回；后台 worker 按 VPC 保序投递；失败退避重试 → 死信。"""

    MAX_RETRY = 3

    def __init__(self, exchange, workers=4):
        self.exchange = exchange
        # 每个 VPC 一条逻辑队列：不同 VPC 并行、同 VPC 严格 FIFO。
        # 为什么不是全局队列：单 VPC 的批量变更会头阻塞其他所有 VPC。
        self._vpc_queues = {}
        self._lock = threading.Lock()
        for i in range(workers):
            threading.Thread(target=self._consume_loop, name="zoob-proxy-%d" % i, daemon=True).start()

    # ---------------- 生产端（Outbox 派发处调用） ----------------

    def put(self, vpc_id, path, value, version):
        self._enqueue(vpc_id, CZoobReqOp("put", path, value, version))

    def delete(self, vpc_id, path, version):
        self._enqueue(vpc_id, CZoobReqOp("delete", path, None, version))

    def _enqueue(self, vpc_id, req):
        with self._lock:
            q = self._vpc_queues.setdefault(vpc_id, queue.Queue())
        q.put(req)

    # ---------------- 消费端 ----------------

    def _consume_loop(self):
        while True:
            vpc_id, req = self._next_request()
            try:
                self.exchange.send(vpc_id, req)
            except Exception as e:
                if req.retry >= self.MAX_RETRY:
                    LOG.error("[zoob] dead letter: vpc=%s path=%s err=%s", vpc_id, req.path, e)
                    continue
                req.retry += 1
                time.sleep(0.2 * req.retry)  # 线性退避
                self._enqueue(vpc_id, req)   # 回队尾：后来者仍排在新值之后

    def _next_request(self):
        while True:
            with self._lock:
                queues = list(self._vpc_queues.items())
            for vpc_id, q in queues:
                try:
                    return vpc_id, q.get_nowait()
                except queue.Empty:
                    continue
            time.sleep(0.01)
