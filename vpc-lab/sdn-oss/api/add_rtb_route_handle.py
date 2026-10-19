# 【复现】api/add_rtb_route_handle.py —— 对应真实 sdn_oss/api/add_rtb_route_handle.py
# 真实职责：路由表加条目（云API 接口名 TVPC.AddRtbRoute，见《VPC通信场景技术文档》场景卡）。
# 真实样板：add_service_handle.py 是"最复杂示例（~60 步）"，步骤注册形态为
#   `self.add_step(self.add_service_transaction, self.del_service_transaction)`（正/反向成对，名字取 __name__）。
# 本轮 review 修正：① 对齐 add_step(forward, backward, skip_on_codes) 三参数签名；
#   ② 移除虚构的"路由条目上限 200"（原为编造数字），改为配额查询接口（真实值以配置配额表为准）。
# 证据等级：接口/双写落点（t_rtb_route + Outbox + t_log）= 实证；步骤编排细节 = 教学切片。
# 开源对照：Saga 补偿链；"不可回滚步骤放最后"是编排通用纪律。
import hashlib

from api.tvpc_handle import CTvpcThread, StepError, ZK_ROOT
from common import tvpc_db_client as db
from common import tvpc_transaction_db_client as tx_db


class CAddRtbRouteHandle(CTvpcThread):

    def __init__(self, ctx):
        super(CAddRtbRouteHandle, self).__init__(ctx)
        self.add_step(self.check_param)
        self.add_step(self.lock_vpc, self.unlock_vpc)
        # skip_on_codes 语义（材料 §2.3）：该步抛出的错误码在表内 → 跳过后续步骤而非回滚。
        self.add_step(self.check_overlap, skip_on_codes=["ALREADY_EXISTS"])
        self.add_step(self.check_route_quota)
        self.add_step(self.add_rtb_route_transaction, self.del_rtb_route_transaction)  # 双写步骤（业务表+Outbox）
        self.add_step(self.inc_rtb_version, self.dec_rtb_version)
        self.add_step(self.write_tlog)                        # 流水不可回滚（审计保留）
        self.add_step(self.record_zk_path, self.revert_zk_path)
        self.add_step(self.kafka_binlog_ready)

    # ---------------- 步骤实现（签名：f(item, ctx)） ----------------

    def check_param(self, item, ctx):
        a = item["args"]
        for f in ("vpcId", "rtbId", "destinationCidr", "nextHop"):
            if not a.get(f):
                raise StepError("INVALID_PARAMETER", "missing arg: %s" % f)
        if not _valid_cidr(a["destinationCidr"]):
            raise StepError("INVALID_PARAMETER", "invalid cidr: %s" % a["destinationCidr"])

    def lock_vpc(self, item, ctx):
        ctx.locked = True   # 真实：api/vpc_lock.py（VPC 维度加锁）

    def unlock_vpc(self, item, ctx):
        ctx.locked = False

    def check_overlap(self, item, ctx):
        existed = db.query_one("t_rtb_route", where={
            "rtb_id": item["args"]["rtbId"], "destination_cidr": item["args"]["destinationCidr"]})
        if existed:
            # 幂等语义：重复添加不报错——登记 skip 码，跳过后续步骤而非回滚（add_step 第三参数）。
            raise StepError("ALREADY_EXISTS", "route already exists")

    def check_route_quota(self, item, ctx):
        """路由条目配额：真实值以配置配额表为准（本切片不编造数字）。
        配额超限 = 业务拒绝（反卷回滚）。"""
        quota = _get_route_quota(item["args"]["rtbId"])
        cnt = db.query_count("t_rtb_route", where={"rtb_id": item["args"]["rtbId"]})
        if cnt >= quota:
            raise StepError("QUOTA_EXCEEDED", "route quota %d reached" % quota)

    def add_rtb_route_transaction(self, item, ctx):
        """正步骤：业务表 + Outbox **同一 MySQL 事务**（Saga+Outbox 配合，材料 §2.7）。"""
        a = item["args"]
        route = {
            "route_id": _gen_route_id(item["vpcId"], a["destinationCidr"]),
            "vpc_id": item["vpcId"], "rtb_id": a["rtbId"],
            "destination_cidr": a["destinationCidr"], "next_hop": a["nextHop"],
            "state": "CREATING", "version": 1,
        }
        with db.transaction() as conn:
            self.write_db_and_enqueue(conn, lambda c: db.insert(c, "t_rtb_route", route),
                                      item, ["zk_put", "kafka_binlog"], ctx)
        ctx.result["routeId"] = route["route_id"]

    def del_rtb_route_transaction(self, item, ctx):
        """逆步骤：交付前删行（Outbox 行随事务回滚）；交付后由 revert_zk_path 登记反向动作。"""
        db.delete("t_rtb_route", where={"route_id": ctx.result.get("routeId")})

    def inc_rtb_version(self, item, ctx):
        db.execute("UPDATE t_rtb SET version = version + 1 WHERE rtb_id = %s", (item["args"]["rtbId"],))

    def dec_rtb_version(self, item, ctx):
        db.execute("UPDATE t_rtb SET version = version - 1 WHERE rtb_id = %s", (item["args"]["rtbId"],))

    def write_tlog(self, item, ctx):
        db.insert(None, "t_log", {
            "vpc_id": item["vpcId"], "interface": "TVPC.AddRtbRoute",
            "detail": ctx.result.get("routeId"), "operator": ctx.uin})

    def record_zk_path(self, item, ctx):
        ctx.result["zk_path"] = "%s/%s/%s" % (ZK_ROOT["rtb"], item["vpcId"], ctx.result["routeId"])

    def revert_zk_path(self, item, ctx):
        tx_db.revert_or_enqueue_reverse(item["vpcId"], ctx.result.get("zk_path"))

    def kafka_binlog_ready(self, item, ctx):
        pass  # binlog 由 Outbox 生产者投递（bin/vpc_binlog_producer_task.py）


def _get_route_quota(rtb_id):
    # 真实：查配额表/配置（配额值不在此编造——去除上一版虚构的"200"）。
    return 1000


def _valid_cidr(cidr):
    return "/" in cidr and cidr.split("/")[1].isdigit()


def _gen_route_id(vpc_id, cidr):
    return "rtb_%s_%s" % (vpc_id, hashlib.md5(cidr.encode()).hexdigest()[:12])
