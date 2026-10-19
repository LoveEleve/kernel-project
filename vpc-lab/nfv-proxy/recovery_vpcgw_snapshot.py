# 【复现】recovery_vpcgw_snapshot.py —— 对应真实 nfv-proxy/recovery_vpcgw_snapshot.py
# 真实职责（23 §3.2）：vpcgw_snapshot 进程入口——为 **vpcgw/hbgw 每个 worker 生成进程粒度快照**并
#   压缩入 CKV、维护版本表（agent_proxy_recovery_db 的 VpcgwWorkerSnapshotVersion/
#   HbgwWorkerSnapshotVersion/PvgwWorkerSnapshotVersion）；`INIT` 参数模式用于首次全量初始化。
# 两个关键设计：
#   ① **进程粒度**：vpcgw 是多 worker 进程（每 worker 处理一个 VPC 子集）——
#      按 worker 切快照 = 崩溃恢复时只重建一个 worker 的状态，而不是全量重启（恢复成本 ∝ 故障域）；
#   ② **入口约定**：gevent monkey.patch_all 必须在文件最前且**禁止被 import**——
#      patch 是进程级全局副作用，被 import 会污染调用方（真实文件里显式注释此约束）。
# 证据等级：职责/版本表/INIT/gevent 约束 = 实证；实现 = 教学等价。
# —— 入口文件：禁止被 import ——
if __name__ != "__main__":
    raise RuntimeError("recovery_vpcgw_snapshot must run as a process, not imported")

# gevent monkey patch（必须在所有业务 import 之前）。
try:
    import gevent.monkey
    gevent.monkey.patch_all()
except ImportError:
    pass

from logger import LOG  # noqa: E402

# 恢复库版本表（真实：recovery_models.py 的 peewee 模型）。
VERSION_TABLES = {
    "vpcgw": "VpcgwWorkerSnapshotVersion",
    "hbgw": "HbgwWorkerSnapshotVersion",
    "pvgw": "PvgwWorkerSnapshotVersion",
}


class WorkerSnapshot(object):
    def __init__(self, gw_type, worker_id, ckv, recovery_db):
        self.gw_type = gw_type
        self.worker_id = worker_id
        self.ckv = ckv
        self.recovery_db = recovery_db

    def build(self):
        """为一个 worker 构建快照：该 worker 负责的全部 VPC 数据 → 压缩 → CKV。
        片键按 worker 隔离：`ckv:{gw_type}:worker{id}:{vpc_id}:{version}`。"""
        vpcs = self.recovery_db.query_list(
            "SELECT vpc_id FROM VpcPartition WHERE gw_type=%s AND worker_id=%s",
            (self.gw_type, self.worker_id))
        for vpc in vpcs:
            payload = self.load_worker_vpc_state(vpc["vpc_id"])
            self.ckv.write_fragments(
                "ckv:%s:worker%d:%s" % (self.gw_type, self.worker_id, vpc["vpc_id"]),
                version=_now_version(), values_iter=[self.compress(payload)])
        self.registry_version(len(vpcs))

    def registry_version(self, vpc_count):
        """版本表登记：WorkerSnapshotVersion(gw_type, worker_id, version, vpc_count, ts)。
        CheckJob/恢复流程按版本表判断"这个 worker 的快照是不是新的"。"""
        self.recovery_db.insert(VERSION_TABLES[self.gw_type], {
            "worker_id": self.worker_id, "version": _now_version(),
            "vpc_count": vpc_count, "ts": _now_version()})
        LOG.info("[snapshot] %s worker%d version registered (%d vpcs)",
                 self.gw_type, self.worker_id, vpc_count)

    def load_worker_vpc_state(self, vpc_id):
        return {}

    def compress(self, payload):
        return payload


def _now_version():
    import time
    return int(time.time())


def main(region=None, init=False):
    """INIT 模式：全量初始化（首次部署/扩容新 worker 时跑）；
    常规模式：周期滚动重建（配合检查版本差异）。"""
    LOG.info("[vpcgw_snapshot] start (init=%s)", init)
    # 真实：遍历本机负责的 worker 集合，逐个 WorkerSnapshot.build()。
    WorkerSnapshot("vpcgw", 0, None, None).build()


if __name__ == "__main__":
    main()
