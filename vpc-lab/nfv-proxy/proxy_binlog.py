# 【复现】proxy_binlog.py —— 对应真实 nfv-proxy/proxy_binlog.py ★ 增量链主线
# 真实职责（23 §3.2）：proxy_binlog 进程——消费 Ckafka 中 vpcoss binlog 消息，按 topic/group
#   写入分区 ZK 对应路径（/BINLOG/...）；**ForeverThread 周期自检、master 变更时自杀重建**。
# 关键机制（材料实证）：
#   ① 消费组 = appID:topic（proxy_meta_info 解析）——位点由 Ckafka 管，重启从位点续；
#   ② 写入分区 ZK：vpc_agent 在另一头 watch /BINLOG（"控制面状态暴露在 ZK 树上"）；
#   ③ master 变更 → 自杀重建：消费与 ZK 拓扑强绑定，拓扑变了旧连接写的路径可能已失效——
#      **重建比"边跑边修"更安全**（watchdog 会拉起，随机延迟防惊群）；
#   ④ ForeverThread 周期自检：心跳/写位点长时间不动 → 视为卡死 → 自杀（对照 sdn_oss 消费端假死教训）。
#   ⑤ 双份进程（proxy_binlog / proxy1_binlog）：独立拉起、独立守护、restart 脚本同时重启两份。
#      （材料未展开双份语义——理解为分载+冗余，标注为推导。）
# 证据等级：①②③④⑤ = 实证（§3.2/§4.1 + bin/restart_proxy_binlog.sh）；本实现为教学等价。
import json
import time

import daemon
from logger import LOG
from proxy_meta_info import Proxy
from zkconn import start_zk

SELF_CHECK_INTERVAL_S = 30
STALE_WRITE_SECONDS = 300     # 写位点 5 分钟未推进 = 卡死（自检阈值，推导）


class ForeverThread(object):
    """周期自检基类（真实同名机制）：业务循环 + 心跳看门狗，卡死 → 自杀换重启。"""

    def __init__(self, name):
        self.name = name
        self._last_progress = time.time()

    def mark_progress(self):
        self._last_progress = time.time()

    def check(self):
        gap = time.time() - self._last_progress
        if gap > STALE_WRITE_SECONDS:
            LOG.error("[%s] no progress for %.0fs — suicide for rebuild", self.name, gap)
            daemon.suicide(reason="%s stale" % self.name)


class ProxyBinlog(ForeverThread):
    def __init__(self, region=None, zone=None):
        super(ProxyBinlog, self).__init__("proxy_binlog")
        self.region = region or "gz"
        self.zone = zone or "zone-a"

    def main(self):
        meta = Proxy(self.region, self.zone)
        self.meta = meta
        # master 变更 → 自杀重建（由 watchdog 拉起；随机延迟防惊群）。
        meta.watch(lambda new_master: daemon.suicide(reason="master_zk changed → %s" % new_master))

        self.zk = start_zk(meta.partition_zk)
        consumer = _kafka_consumer(topic=meta.topic, group=meta.consumer_group)
        LOG.info("[proxy_binlog] consuming %s group=%s", meta.topic, meta.consumer_group)

        while not daemon.stopping():
            msg = consumer.poll(timeout_ms=1000)
            if msg is None:
                self.check()          # 空闲也自检（空闲 ≠ 卡死；progress 由实际写入刷新）
                continue
            self.handle(msg)

    def handle(self, msg):
        """binlog 消息 → 分区 ZK。写路径（材料实证）：
        `/BINLOG/{vpcId}/{资源组}/...`——证据：tools_clean_expire_group.py
        "清理分区 ZK /BINLOG/{vpcId}/VM 下小于阈值的过期 group 子树"。
        payload 带 epoch：VPC 未正常 TERMINATE 会残留"多 epoch binlog"
        （排查工具 tools_check_zk_last_epoch_binlog.py）——清理侧的坑，写入侧先埋好字段。"""
        try:
            event = json.loads(msg.value)
        except ValueError:
            LOG.warn("[proxy_binlog] bad message, skip")
            return
        zk_path = "/BINLOG/%s/%s/%s" % (
            event["vpcId"], event.get("group", event["table"]), event.get("pk", ""))
        self.zk_set(zk_path, msg.value)
        self.mark_progress()

    def zk_set(self, path, value):
        # 真实：CZoo（zk_op.py）——节点读/写/递归删除封装。
        LOG.info("[proxy_binlog] ZK set %s (%d B)", path, len(value))


def _kafka_consumer(topic, group):
    """等价桩（真实：kafka-python KafkaConsumer，gevent 打补丁）。"""
    class _C(object):
        def poll(self, timeout_ms):
            time.sleep(timeout_ms / 1000.0)
            return None
    return _C()


def main(region=None, init=False):
    ProxyBinlog(region=region).main()
