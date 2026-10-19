# 【复现】common/zklock.py —— 对应真实 sdn_oss/common/zklock.py（"基于 ZK 的分布式锁（connect()/Lock()）"）
# 本轮 review 修正（归因）："LOST→自杀→雪崩" 的原发地是 **nfv-proxy 的 proxy_merge worker**
# （docs-by-topic/08 §9.2："ZK 短暂网络抖动 → 所有持锁/等锁的 proxy_merge worker 同时检测到 LOST
#  → 同时 suicide → ZK 集群全体雪崩。这是 nfv-proxy 从 ZK 锁迁移到 DB 锁的直接原因"），
# 不是 sdn_oss 的 zklock 自身——sdn_oss 的 zklock 是同族 ZK 锁的另一处使用面（VPC 维度加锁走它）。
# 迁移结论（材料）：DB 锁容忍 2 次心跳失败（30 秒）再动作，而不是"断开即自杀"——可用性优先级反了。
# 证据等级：文件/方法名/教训链条 = 实证；本实现为教学等价。
# 开源对照：ZooKeeper 官方 Recipes（ephemeral sequential）；Kazoo（nfv-proxy 用的客户端）。
import threading
import time

from common.log import LOG


class ZkLock(object):
    def __init__(self, zk_session, path):
        self._session = zk_session
        self._path = "/zklock" + path        # 顺序临时节点前缀
        self._locked = False
        self._local = threading.local()

    def connect(self):
        self._session.watch_session(self._on_session_event)

    # ---------------- 会话事件 ----------------

    def _on_session_event(self, state):
        """反面教材（材料实证）：LOST 的处置若是"立即自杀"，抖动会被放大——
        nfv-proxy 当年的雪崩链：ZK 抖动 → proxy_merge 全体 LOST → 全体 suicide →
        服务整体不可用（远超抖动本身）。迁移后的 DB 锁：容忍 2 次心跳失败（30s）才动作。
        本文件保留 ZK 锁实现，作为"为什么热路径锁不该用 ZK"的对照物。"""
        if state == "LOST":
            LOG.error("[zklock] session LOST — 勿模仿'立即自杀'（见 nfv-proxy 雪崩教训）")
            # 目标态：锁自动失效、进程存活、业务靠"持锁身份校验"识别失去锁
            # （对照 vpc-fusion 的 Ownership 校验：谁失去租约，谁的操作被拒绝）。

    # ---------------- 加锁（ZK 官方 recipe：顺序临时节点 + 监听前驱） ----------------

    def Lock(self, timeout_s=5):
        node = self._session.create_ephemeral_sequential(self._path)
        self._local.node = node
        deadline = time.time() + timeout_s
        while True:
            parent = self._path.rsplit("/", 1)[0]
            kids = sorted(self._session.get_children(parent))
            me = node.rsplit("/", 1)[1]
            prev = _predecessor(kids, me)
            if prev is None:
                self._locked = True   # 同级最小 = 持锁
                return True
            if time.time() > deadline:
                self._session.delete(node)
                return False
            self._session.watch_once(parent + "/" + prev)  # 等前驱消失
            _ = self._session.wait_notification(timeout=timeout_s)

    def Unlock(self):
        if self._locked:
            self._session.delete(self._local.node)
            self._locked = False


def _predecessor(sorted_kids, node):
    idx = sorted_kids.index(node) if node in sorted_kids else -1
    return sorted_kids[idx - 1] if idx > 0 else None


class _ZkSession(object):
    """会话桩（真实为业务封装的 ZK 客户端）。"""

    def watch_session(self, cb):
        pass

    def create_ephemeral_sequential(self, path):
        return path + "/000001"

    def get_children(self, path):
        return []

    def watch_once(self, path):
        pass

    def wait_notification(self, timeout=0):
        time.sleep(timeout)

    def delete(self, node):
        pass
