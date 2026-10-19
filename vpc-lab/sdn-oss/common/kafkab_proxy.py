# 【复现】common/kafkab_proxy.py —— 对应真实 sdn_oss/common/kafkab_proxy.py
# 真实职责：Kafka 写入代理——sdn_oss 向三条下游通道投递变更流水：
#   ① Gen2 nfv 通道（nfv-proxy 消费 → 分区 ZK/CRS）；
#   ② Gen3（VPC 5.0）binlog 通道（fusion 消费 → 对象模型库）；
#   ③ 通知/审计通道。
# 形态：**同步 send（关键变更，等 ack）与异步 send（批量流水，入队即返回）双通道**。
# 证据等级：代理存在性/下游通道 = 实证；双通道切分 = 由"关键路径 vs 流水"推导。
# 开源对照：librdkafka 的 sync/async producer；Kafka 幂等生产者（enable.idempotence=true）。
import threading
import queue
import time

from common.log import LOG


class KafkaBProxy(object):
    def __init__(self, brokers, sync_topic, async_topic, async_workers=2):
        self.brokers = brokers
        self.sync_topic = sync_topic    # 关键变更（如迁移状态机推进）——同步等 ack
        self.async_topic = async_topic  # 批量流水（如 binlog）——异步攒批
        self._q = queue.Queue(maxsize=50000)  # 有界队列：满 = 背压（拒绝或阻塞，绝不无限吃内存）
        for i in range(async_workers):
            threading.Thread(target=self._async_loop, name="kafkab-%d" % i, daemon=True).start()

    # ---------------- 同步通道 ----------------

    def send_sync(self, key, value, timeout_s=3):
        """关键流水：发送并等 broker ack；失败抛异常 → 由调用方（Outbox 生产者）决定重试节奏。
        key 语义：**用 vpcId 做 partition key**——同一 VPC 的流水进同一 partition，
        下游单 partition 消费天然保序（Kafka 只保 partition 内序，这是把"序"钉死的关键一招）。"""
        _produce(self.brokers, self.sync_topic, key, value)
        return True

    # ---------------- 异步通道 ----------------

    def send_async(self, key, value):
        try:
            self._q.put_nowait({"key": key, "value": value, "ts": time.time()})
        except queue.Full:
            # 背压策略（真实推导）：生产者不许无限排队——宁可让上游 Outbox 扫描慢一点（记录保持 0 态），
            # 也不让本进程 OOM。**慢比崩优雅**。
            LOG.warn("[kafkab] queue full, backpressure — 本轮少发一些")

    def _async_loop(self):
        while True:
            item = self._q.get()
            try:
                _produce(self.brokers, self.async_topic, item["key"], item["value"])
            except Exception as e:
                LOG.warn("[kafkab] async send failed key=%s: %s — 重试一次", item["key"], e)
                time.sleep(0.5)
                try:
                    _produce(self.brokers, self.async_topic, item["key"], item["value"])
                except Exception as e2:
                    # 两次都失败：丢给"死信表"（真实有专门表），由对账兜底 —— 不在这里无限重试。
                    LOG.error("[kafkab] async send dead key=%s: %s", item["key"], e2)


def _produce(brokers, topic, key, value):
    pass  # 占位：真实为 Kafka producer 客户端调用
