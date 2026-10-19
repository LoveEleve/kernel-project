# 【复现】api/Vpc5/decorator.py —— 对应真实 sdn_oss/api/Vpc5/ 目录（decorator/Eni/Subnet/Vm/Rule/Rtable…）
# 真实职责：VPC5 迁移适配层——@support_vpc5 装饰器把 CVpc5TvpcThread Mixin 挂进旧 Handle 类，
#   让同一个旧接口在新旧对象模型并存期"按灰度切到 VPC5 语义"。迁移切片依据由
#   vpc5-object-api-scanner 扫描（材料实证：sdn_oss/skills/vpc5-object-api-scanner/，23 文件；出处 24 清单:506）。
# 证据等级：目录/装饰器/Mixin 形态/scanner = 实证；prepare/extract 细节 = 推导（教学切片）。
# 开源对照：Python 装饰器做 AOP（Django middleware 同理）；"新旧双写+读切换"是经典迁移模式
#   （业界先例：Facebook 的 MySQL→TAO 过渡、双读校验）。
from common.log import LOG


def support_vpc5(gray_uins=None):
    """类装饰器：给旧 Handle 挂 VPC5 Mixin。

    用法（迁移后的旧接口文件里就多这一行）：
        @support_vpc5(gray_uins={"12345", "67890"})
        class CAddRtbRouteHandle(CTvpcThread):
            ...
    """
    def wrap(handle_cls):
        # 关键决定：**不修改旧类的方法体**——开关注入的是 Mixin，
        # 旧代码在新旧并存期保持"一份逻辑、两个对象模型"。
        handle_cls._vpc5_mixin = CVpc5TvpcThread(gray_uins or set())
        return handle_cls
    return wrap


class CVpc5TvpcThread(object):
    """Mixin：为旧 Handle 提供 VPC5 双模型能力。挂载点 = CTvpcThread.execute() 开头的 prepare()。"""

    def __init__(self, gray_uins):
        self.gray_uins = gray_uins

    def prepare(self, ctx):
        # 灰度判定：仅白名单 uin 走 VPC5 语义；其余走旧模型（迁移期间两套都必须是正确路径）。
        ctx.vpc5_mode = ctx.uin in self.gray_uins
        if not ctx.vpc5_mode:
            return
        LOG.info("[vpc5] gray hit: uin=%s vpc=%s", ctx.uin, ctx.vpc_id)
        # 新对象模型适配器：旧接口参数(rtbId/destinationCidr) → 新模型资源对象。
        # 新模型要点（材料实证）：资源带全局唯一 ID + 版本号，路由挂在"对象树"而非平铺表。
        ctx.vpc5 = Vpc5ObjectAdapter(ctx)

    def extract_vpc5_fields(self, ctx, old_fields):
        """旧字段 ↔ 新字段的映射表驱动的转换（真实实现为按接口逐字段对照）。
        未迁移字段保持原样——**不允许"顺手现代化"**：转换只做显式声明的映射。"""
        if not getattr(ctx, "vpc5", None):
            return old_fields
        return ctx.vpc5.map_fields(old_fields)


class Vpc5ObjectAdapter(object):
    """把请求翻译到 VPC5 对象模型（本切片只演示路由一例）。"""

    def __init__(self, ctx):
        self.ctx = ctx

    def map_fields(self, old_fields):
        return {
            "objectType": "VpcRoute",
            "objectId": old_fields.get("routeId") or old_fields.get("rtbId"),
            "props": {
                "destinationCidrBlock": old_fields.get("destinationCidr"),
                "nextHop": old_fields.get("nextHop"),
            },
            "revision": old_fields.get("version"),
        }
