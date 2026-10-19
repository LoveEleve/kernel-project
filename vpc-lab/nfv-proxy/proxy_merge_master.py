# 【复现】proxy_merge_master.py —— 对应真实 nfv-proxy/proxy_merge_master.py
# 真实职责（23 §3.2）：合并调度主进程——从 **CKV pending 管道**取任务、**计数限流**分发 merge 任务
#   （材料原文："解决 vm_prop 压缩耗时过长问题"）。
# 调度问题的形状：
#   merge 任务不均质——vm_prop（全 VPC 的 VM 属性）又大又频繁，单次压缩可能就是几十秒；
#   如果 merge 进程池被几个 vm_prop 任务占满，rtable/service 这些"关键路径"数据就饿死。
#   解法 = **按类型的并发计数上限 + pending 管道缓冲**：快类型优先、慢类型限并发，
#   把"任务到达速率"和"处理能力"解耦（排队而不是丢弃）。
# 证据等级：pending 管道/计数限流/vm_prop 动机 = 实证；实现 = 教学等价。
import threading
import time

from logger import LOG

# 每类 merge 任务的最大并发（计数限流；vm_prop 单类限 1 并发——最慢的跑不并行占满池子）。
# （限量值为示意——材料给"计数限流"机制与 vm_prop 动机，未给具体数值。）
CONCURRENCY_LIMIT = {
    "vm_prop": 1,
    "rtable": 4,
    "service": 4,
    "gw_config": 2,
    "default": 2,
}


class ProxyMergeMaster(object):
    def __init__(self, ckv, worker_pool):
        self.ckv = ckv
        self.worker_pool = worker_pool          # 真实：fork merge 进程池 / 分发给多台
        self._inflight = {}                     # merge_class → 在途计数
        self._lock = threading.Lock()

    def main(self):
        while True:
            pending = self.read_pending_pipeline()      # CKV pending 管道（生产者=快照/对账侧）
            if not pending:
                time.sleep(1)
                continue
            for task in self.schedule(pending):
                self.dispatch(task)

    def schedule(self, pending):
        """按类型限流后的可派发集合（公平性：不饿死快类型，不放开慢类型）。"""
        out = []
        for task in pending:
            cls = task["merge_class"]
            limit = CONCURRENCY_LIMIT.get(cls, CONCURRENCY_LIMIT["default"])
            with self._lock:
                if self._inflight.get(cls, 0) >= limit:
                    continue                       # 超限：留在管道里等（排队不丢弃）
                self._inflight[cls] = self._inflight.get(cls, 0) + 1
            out.append(task)
        return out

    def dispatch(self, task):
        LOG.info("[merge_master] dispatch %s vpc=%s (%d inflight)",
                 task["merge_class"], task["vpc_id"], self._inflight[task["merge_class"]])
        self.worker_pool.submit(task, on_done=lambda: self._done(task["merge_class"]))

    def _done(self, cls):
        with self._lock:
            self._inflight[cls] = max(0, self._inflight.get(cls, 1) - 1)

    def read_pending_pipeline(self):
        # 真实：CKV pending 管道（2010+ 组正在合并时会被限流削峰）。
        return []


class ProxyLazyMerge(object):
    """proxy_lazymerge 进程（同文件对照，真实为独立文件/进程）：
    对 6 类高频公共数据做**惰性合并**——不是每次变更都合并，而是攒到阈值/空闲窗口再折——
    降低高频数据的合并压力。
    （材料实证 3 类：全局路由 / rtb_route / vm_prop "等 6 类"；另 3 类为示意。）"""

    LAZY_CLASSES = ["global_route", "rtb_route", "vm_prop", "vip_tgw", "subnet_rule", "host_prop"]

    def main(self):
        while True:
            for cls in self.LAZY_CLASSES:
                self.lazy_merge_one(cls)
            time.sleep(30)

    def lazy_merge_one(self, cls):
        pass  # 攒批阈值 + 空闲窗口判定后，复用 proxy_merge.merge_one 的折叠流程
