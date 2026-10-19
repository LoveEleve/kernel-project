# 【复现】proxy_merge.py —— 对应真实 nfv-proxy/proxy_merge.py
# 真实职责（23 §3.2）：proxy_merge 进程——把 ZK 中**待合并的 binlog 分组**合并为新快照并压缩入 CKV，
#   覆盖路由表/service/网关配置等全部数据类型（MERGE_CLASS 映射）。
# 为什么要有 merge（快照+binlog 两级读取的闭合点）：
#   agent 读数据 = 快照（全量基线）+ /BINLOG（增量）。binlog 会无限增长——
#   必须有人定期把增量"折叠"进快照（新的基线版本），binlog 才能被裁掉。
#   merge 就是这个折叠器；它让"全量快照"退场为"增量合并"，把全量重拉的代价摊平。
# 证据等级：职责/MERGE_CLASS 覆盖全部数据类型 = 实证；折叠流程 = 教学等价。
import time

from logger import LOG

# MERGE_CLASS 映射（真实覆盖全部数据类型；6 类高频/公共数据另有 proxy_lazymerge 惰性合并）。
MERGE_CLASS = {
    "rtable": "路由表(vmIp 维度)",
    "rtable_vpcgw": "vpcgw 路由表(VIP 维度)",
    "service": "Service/Endpoint",
    "gw_config": "网关配置（Host/Vpcgw/Natgw/Pvgw/Hbgw/...）",
    "vpc_config": "VPC 配置（七类）",
    "acl": "ACL 规则（Host/Gw）",
    "global_route": "全局路由（公共数据）",
    "rtb_route": "路由表条目",
    "vm_prop": "VM 属性（**压缩耗时最长**——见 merge_master 限流）",
    "subnet_rule": "子网规则",
    "vip_tgw": "VIPTGW（公共数据）",
}


class ProxyMerge(object):
    def __init__(self, region, zone, ckv):
        self.region = region
        self.zone = zone
        self.ckv = ckv

    def main(self):
        while True:
            task = self.fetch_merge_task()      # 从"待合并 binlog 分组"取任务（真实由 merge_master 分发/pending 管道）
            if task is None:
                time.sleep(1)
                continue
            self.merge_one(task)

    def merge_one(self, task):
        """折叠一个分组：旧快照片 + 该组的 binlog 事件 → 新快照片 → 压缩入 CKV → 登记新版本。"""
        cls = task["merge_class"]
        vpc_id = task["vpc_id"]
        LOG.info("[merge] %s vpc=%s (%s)", cls, vpc_id, MERGE_CLASS.get(cls, "?"))
        base = self.load_snapshot_fragment(cls, vpc_id)     # 旧基线
        events = self.load_binlog_group(cls, vpc_id)        # /BINLOG 该组待合并事件
        merged = self.apply_events(base, events)            # 折叠（真实：按主键 upsert/delete）
        self.ckv.write_fragments(self.prefix(cls, vpc_id), task["new_version"], [merged])
        self.ckv.write_version_list(self.prefix(cls, vpc_id), task["new_version"], [])
        self.cut_binlog(cls, vpc_id, up_to=task["up_to_trans_id"])  # 裁掉已折叠的 binlog

    # -------- 等价桩 --------
    def fetch_merge_task(self):
        return None

    def load_snapshot_fragment(self, cls, vpc_id):
        return {}

    def load_binlog_group(self, cls, vpc_id):
        return []

    def apply_events(self, base, events):
        return base

    def cut_binlog(self, cls, vpc_id, up_to):
        pass

    def prefix(self, cls, vpc_id):
        return "ckv:%s:%s:%s:%s" % (cls, self.region, self.zone, vpc_id)
