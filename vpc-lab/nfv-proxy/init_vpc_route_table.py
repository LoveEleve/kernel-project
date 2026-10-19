# 【复现】init_vpc_route_table.py —— 对应真实 nfv-proxy/init_vpc_route_table.py
# 真实职责（23 §3.3）：路由表快照 CCkvRouteTable——**vmIp 维度，V4/V6 两套**。
# "vmIp 维度"的含义：路由表按"目的 IP → 下一跳(母机)"聚合存储——
#   数据面查表模型（查 100.200.1.1 找到处理它的母机），与 sdn_oss 侧的"资源维度"不同：
#   **同一份配置，控制面按资源建模、下发面按转发模型建模**——快照是两种建模的翻译层。
# 证据等级：类名/维度/双栈 = 实证；实现 = 教学等价。
from init_vpc_common import CVpcSyn


class CCkvRouteTable(CVpcSyn):
    PREFIX_V4 = "ckv:rtable:v4"
    PREFIX_V6 = "ckv:rtable:v6"

    def iter_values(self, todo):
        """按 vmIp 维度聚合：SELECT ip, host_ip FROM t_rtable WHERE vpc_id=?，
        每条 → 一个 value（或按母机聚合）；todo 限定本次要重做的 key 集合。"""
        for ip in todo:
            row = self.db.query_one(
                "SELECT ip, host_ip, state FROM t_rtable WHERE vpc_id=%s AND ip=%s",
                (self.vpc_id, ip))
            if row:
                yield {"ip": row["ip"], "nextHop": row["host_ip"], "state": row["state"]}

    def current_keys(self):
        rows = self.db.query_list("SELECT ip FROM t_rtable WHERE vpc_id=%s", (self.vpc_id,))
        return [r["ip"] for r in rows]

    def sync(self):
        """V4/V6 两套各自独立走一遍基类模板（片键前缀不同）。"""
        super(CCkvRouteTable, self).sync()


class CCkvRouteTableVpcgw(CVpcSyn):
    """vpcgw 路由表：**VIP 维度**（另一套建模——VIP 是网关侧转发锚点）。"""
    PREFIX = "ckv:rtable:vpcgw"

    def iter_values(self, todo):
        for vip in todo:
            yield {"vip": vip}

    def current_keys(self):
        return []
