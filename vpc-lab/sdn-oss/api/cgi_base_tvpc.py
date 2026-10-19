# 【复现】api/cgi_base_tvpc.py —— 对应真实 sdn_oss/api/cgi_base_tvpc.py
# 真实职责（docs-by-topic 24 §3.1.1 + VPC控制面.md:12751）：TVPC CGI 基类 CCgiBaseTvpc——
#   校验请求信封格式、region 校验、配额限流策略、按 interfaceName 取得 Handle 并执行。
# 本轮 review 修正（对齐材料真实信封，原 appId/uin/region/args 为编造）：
#   对外协议 = 自定义 JSON 信封（`version/caller/callee/eventId/interface{interfaceName,para}`），
#   详见 docs/Readme.adoc；生产调用地址形如 http://${region}.oss.vpc.tencentyun.com:8080/tvpc/api
#   （VPC控制面.md:12686）——region 校验即 host 子域与本地部署 region 比对。
#   请求级上下文真实类名 = CDc（common/dc.py："在 CGI/Handle 链路间存取 interfaceName 等追踪数据"）。
# 证据等级：信封字段/请求上下文类 = 实证；限流维度 = 24 文"配额限流策略"表述。 
# 开源对照：API Gateway 的"信封校验/限流"层；sentry 式统一异常收口。
from common.cgi_help import (
    RET_OK, RET_INVALID_PARAMETER, RET_REGION_MISMATCH, RET_RATE_LIMIT, RET_UNKNOWN_INTERFACE, RET_DB_ERROR,
)
from common import tvpc_token_bucket_redis as token_bucket

REQUIRED_FIELDS = ("version", "caller", "callee", "eventId", "interface")


class CCgiBaseTvpc(object):
    def __init__(self, environ, local_region="gz"):
        self.environ = environ
        self.local_region = local_region

    # ---------------- ① 信封校验 ----------------

    def validate_envelope(self, req):
        if not isinstance(req, dict):
            raise ValueError("request must be a json object")
        for f in REQUIRED_FIELDS:
            if f not in req:
                raise ValueError("missing field: %s" % f)
        iface = req["interface"]
        if not isinstance(iface, dict) or not iface.get("interfaceName"):
            raise ValueError("interface.interfaceName required")

    # ---------------- ② region 校验 ----------------

    def check_region(self, req):
        # 生产调用地址为 ${region}.oss.vpc...——region 由 host 子域携带（VPC控制面.md:12686）。
        host = (self.environ.get("HTTP_HOST") or "")
        host_region = host.split(".oss.vpc")[0] if ".oss.vpc" in host else self.local_region
        if host_region != self.local_region:
            raise _BizError(RET_REGION_MISMATCH, "region mismatch: %s" % host_region)

    # ---------------- ③ 配额限流 ----------------

    def check_quota(self, req, interface_name):
        # 分布式令牌桶（common/tvpc_token_bucket_redis.py）。维度：接口 × 调用方（信封 caller）——
        # 防止单个调用方打爆全服务。
        key = "tvpc:limit:%s:%s" % (interface_name, req["caller"])
        if not token_bucket.acquire(key):
            raise _BizError(RET_RATE_LIMIT, "rate limited")

    # ---------------- ④ 分发执行 ----------------

    def execute(self, req, registry):
        try:
            self.validate_envelope(req)
            self.check_region(req)
            interface_name = req["interface"]["interfaceName"]
            self.check_quota(req, interface_name)

            handle_cls = registry.get(interface_name)
            if handle_cls is None:
                raise _BizError(RET_UNKNOWN_INTERFACE, "unknown interface: %s" % interface_name)

            cdc = CDc(req, self.environ)
            handle = handle_cls(cdc)
            # Saga 引擎按"对象列表"执行（单对象接口 = 一个 item）；批量为同类接口服务。
            result = handle.execute([cdc.build_item()])
            return {"retCode": RET_OK, "retMsg": "ok", "data": cdc.result}
        except _BizError as e:
            return {"retCode": e.code, "retMsg": str(e)}
        except ValueError as e:
            return {"retCode": RET_INVALID_PARAMETER, "retMsg": str(e)}
        except Exception as e:
            # 未预期异常：回滚已在 Saga 层内完成（含 Redis 缓存 + 告警），这里只收口响应。
            return {"retCode": RET_DB_ERROR, "retMsg": "internal error: %s" % e}


class _BizError(Exception):
    def __init__(self, code, msg):
        super(_BizError, self).__init__(msg)
        self.code = code


class CDc(object):
    """请求级数据上下文（真实：common/dc.py 的 CDc；在 CGI/Handle 链路间存取追踪数据）。"""

    def __init__(self, req, environ):
        self.interface_name = req["interface"]["interfaceName"]
        self.caller = req["caller"]
        self.callee = req["callee"]
        self.event_id = req["eventId"]
        self.args = req["interface"].get("para") or {}
        self.uin = str(req["caller"]).split("@")[0]
        self.vpc_id = self.args.get("vpcId")
        self.result = {}
        self.locked = False
        self.trace_id = self.event_id  # eventId 本身即全链路追踪号

    def build_item(self):
        """把 CDc 转成 Saga 处理的"对象"：action 由接口名前缀推导（add_/del_/update_）。"""
        action = "UPDATE"
        if self.interface_name.startswith("TVPC.Add"):
            action = "CREATE"
        elif self.interface_name.startswith("TVPC.Del"):
            action = "DELETE"
        item = dict(self.args)
        item.update({"args": self.args, "vpcId": self.vpc_id, "action": action,
                     "transId": None, "epoch": self.args.get("epoch", 0)})
        return item
