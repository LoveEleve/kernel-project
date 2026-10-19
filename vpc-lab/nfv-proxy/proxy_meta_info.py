# 【复现】proxy_meta_info.py —— 对应真实 nfv-proxy/proxy_meta_info.py
# 真实职责（23 §3.1）：Proxy 类——proxy 元信息（region/zone/partition/master_zk/ckv/topic 消费组）的
#   注册、解析与 ZK 状态 watch；**几乎所有进程都用它自举**。
# 元信息从哪来：
#   - 静态：config_all_partition.py（gen_config.py 生成）region→集群→zone→"分区 ZK 地址|appID:topic|cdb 连接串"；
#   - 动态：分区 ZK 上的 master/topology 节点（watch 变更 → 回调）。
# 证据等级：元信息字段/自举用途 = 实证；注册/watch 细节 = 教学等价。
from logger import LOG

# 分区静态表（真实：config_all_partition.py，由 gen_config.py 从 Rainbow + 分区 ZK 生成）。
PARTITION_TABLE = {
    ("gz", "zone-a"): {
        "partition_zk": "part-zk-gz-a:2181",
        "app_topic": "vpcoss_gz_a:binlog",
        "cdb_ro": "cdb-ro-gz-a:3306/nfv_proxy",
    },
    ("sh", "zone-a"): {
        "partition_zk": "part-zk-sh-a:2181",
        "app_topic": "vpcoss_sh_a:binlog",
        "cdb_ro": "cdb-ro-sh-a:3306/nfv_proxy",
    },
}


class Proxy(object):
    """proxy 自举句柄。构造即完成【解析 → 注册 → watch】三步。"""

    def __init__(self, region, zone, need_watch=True):
        self.region = region
        self.zone = zone
        static = PARTITION_TABLE.get((region, zone))
        if static is None:
            raise ValueError("no partition for %s/%s" % (region, zone))
        self.partition_zk = static["partition_zk"]
        self.app_id, self.topic = static["app_topic"].split(":")
        self.consumer_group = "%s:%s" % (self.app_id, self.topic)  # 消费组命名（推导：以 appID:topic 为据）
        self.cdb_ro = static["cdb_ro"]
        self.master_zk = None      # 动态解析：分区 ZK 上的 master 节点
        self.ckv_addr = None       # 经北极星解析的 CKV 实例
        self._zk = None
        if need_watch:
            self.register()
            self.resolve_master()
            self.watch(self._on_topology_change)

    # ---------------- 注册 / 解析 / watch ----------------

    def register(self):
        """把自己的 region/zone/partition 写成临时节点（monitor 靠它盘点进程覆盖）。"""
        self._zk_path_self = "/PROXY/%s/%s/members/%s" % (self.region, self.zone, "self-pid")
        LOG.info("[meta] registered %s", self._zk_path_self)

    def resolve_master(self):
        """解析 master_zk（分区 ZK 拓扑权威）。"""
        self.master_zk = "master-zk.%s:2181" % self.region

    def watch(self, cb):
        """watch 拓扑节点；变更 → 回调（binlog 进程据此自杀重建消费）。"""
        self._watch_cb = cb

    def _on_topology_change(self, new_master):
        LOG.warn("[meta] topology changed: master_zk %s → %s", self.master_zk, new_master)
        if self._watch_cb:
            self._watch_cb(new_master)
