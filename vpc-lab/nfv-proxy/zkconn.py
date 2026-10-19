# 【复现】zkconn.py —— 对应真实 nfv-proxy/zkconn.py
# 真实职责（23 §3.1）：start_zk——KazooClient 连接（域名先解析成 IP）带重试，**超限自杀**。
# 与"连接"的边界（工程纪律）：
#   - 启动期：连不上 = 服务没起来（重试后自杀，交给 watchdog 重启）——这是启动失败，不是运行期故障；
#   - 运行期：会话短暂 LOST 不自杀（对照 lock.py 雪崩教训：把可用性交给重试，而不是杀死进程）。
# 证据等级：重试+超限自杀 = 实证；启动/运行期边界切分 = 由雪崩教训推导。
import time

from logger import LOG
from utils import domain2ip

RETRY_MAX = 10          # 重试次数（示意——材料未给具体值）
RETRY_INTERVAL_S = 3    # 间隔（示意）


def start_zk(host, read_timeout=30.0):
    """返回 ZK 客户端句柄。域名先解析成 IP；重试 RETRY_MAX 次仍失败 → 自杀。"""
    ip = domain2ip(host)
    for attempt in range(1, RETRY_MAX + 1):
        try:
            client = _kazoo_start(ip, read_timeout)
            LOG.info("[zkconn] connected to %s (attempt %d)", ip, attempt)
            return client
        except Exception as e:
            LOG.warn("[zkconn] connect %s failed (%d/%d): %s", ip, attempt, RETRY_MAX, e)
            time.sleep(RETRY_INTERVAL_S)
    # 重试超限：**启动期自杀**——watchdog 会在下一轮拉起（带退避）。
    from daemon import suicide
    suicide(reason="zk connect exhausted: %s" % ip)


def _kazoo_start(ip, read_timeout):
    """等价桩（真实：KazooClient(hosts=ip, timeout=read_timeout).start()）。"""
    return _KazooStub(ip)


class _KazooStub(object):
    def __init__(self, ip):
        self.ip = ip
