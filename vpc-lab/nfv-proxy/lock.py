# 【复现】lock.py —— 对应真实 nfv-proxy/lock.py（ZkLock）
# 真实职责（docs-by-topic/23 §3.1）：基于 LOCK_ZK_HOST 配置 ZK 的分布式锁，
#   锁路径带 region 前缀（/PROXY/{REGION}/LOCK/...）。
# ★ 本文件是"分布式事故活化石"（材料实证，docs-by-topic/08 §9.2 + 表格行 510）：
#   雪崩链：ZK 网络抖动 → 所有持锁/等锁的 proxy_merge worker 同时 LOST →
#           同时 daemon.suicide() → 服务整体不可用（远超抖动本身）。
#   这是 nfv-proxy **从 ZK 锁迁移到 DB 锁的直接原因**。
#   DB 锁的差异（材料原文）：**容忍 2 次心跳失败（30 秒）**再动作，给 DB 恢复机会——
#   而不是"断开即自杀"。可用性优先级：锁的存亡 ≠ 进程的存亡。
# 证据等级：锁路径/迁移原因/DB 锁特性 = 实证；本实现为教学等价（Kazoo → 等价桩）。
# 开源对照（R3 已用克隆代码核验，refs/kazoo/kazoo/recipe/lock.py）：Kazoo Lock recipe——
#   临时顺序节点（ephemeral+sequence）+ 监听前驱 + `is_acquired` 自省；
#   注意 recipe 以异常（LockLost/LockTimeout）告知"你已丢锁"，**恢复动作由业务决定**——
#   "丢锁即杀进程"是业务加的策略（雪崩正源于此），不是 ZK/Kazoo 的要求。
import time

from logger import LOG
from utils import domain2ip  # ZK 域名先解析成 IP（避免 DNS 抖动导致的伪"断连"）


class ZkLock(object):
    def __init__(self, lock_zk_host, region, name):
        self._host = domain2ip(lock_zk_host)
        self._base = "/PROXY/%s/LOCK/%s" % (region, name)
        self._lk = None
        self._held = False

    def connect(self):
        # 真实：KazooClient(hosts=self._host) + start()；
        # 会话监听处置遵循 zkconn.py 的重试策略（超限记录告警，**不在锁层自杀**——
        # 迁移 DB 锁后：锁失效 ≠ 进程死亡，业务靠持锁校验识别自己已失去锁）。
        pass

    def acquire(self, timeout_s=10):
        """顺序临时节点 + 监听前驱（ZK 官方 recipe）。"""
        self._node = self._zk_create_ephemeral_sequential(self._base)
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            parent = self._base.rsplit("/", 1)[0]
            kids = sorted(self._zk_children(parent))
            me = self._node.rsplit("/", 1)[1]
            idx = kids.index(me) if me in kids else -1
            if idx <= 0:                      # 同级最小 = 持锁
                self._held = True
                return True
            self._zk_watch(parent + "/" + kids[idx - 1])   # 等前驱消失
            time.sleep(0.05)
        self._zk_delete(self._node)
        return False

    def release(self):
        if self._held:
            self._zk_delete(self._node)
            self._held = False

    # ------ ZK 等价桩（真实：KazooClient 同步 API） ------
    def _zk_create_ephemeral_sequential(self, path):
        return path + "/000001"

    def _zk_children(self, path):
        return []

    def _zk_watch(self, path):
        pass

    def _zk_delete(self, path):
        pass


def on_session_lost():
    """反面教材入口（保留历史形态供对照）：
    ❌ 旧版：LOST → LOG + daemon.suicide()（08 §9.2 雪崩链的起点）
    ✅ 迁移后：锁自动失效，进程存活；操作前校验"我是否仍持锁"（对照 vpc-fusion 的 Ownership 校验）"""
    LOG.error("[lock] session LOST — [历史] 此处曾触发自杀；[目标态] 仅失效锁，进程存活")
