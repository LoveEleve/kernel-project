# 【复现】api/CheckMigrate/check_task_item_handle.py —— 对应真实 sdn_oss/api/CheckMigrate/ 目录
# 真实职责：跨 VPC 迁移的前置检查接口族。**本轮 review 修正**：接入 test-good/04-others/is_vif_ready.md
# 全文实证（bugfix/is_vif_ready, vpc_agent, commit 84123306+5df99093, release/4.12.0）。
# 完整事故链（材料原文口径）：
#   ① 调用侧：sdn_oss 的 CheckMigrate **逐个 ENI 串行**调用 vpc_agent 的 is_vif_ready，单次超时 60 秒；
#   ② agent 侧：is_vif_ready 整体包在 `with state.mutex:`（state.py:370 全局锁）里；
#   ③ 冲突方：LightWeightAccount.do_account() 对账流程在整个对账周期（40+ 检查函数，数秒~数十秒）持同一把锁；
#   ④ 结果：对账一启动 → is_vif_ready 全部阻塞 → 60 秒超时堆积 → 跨 VPC 迁移失败率升高。
# 修复（agent 侧，2 文件 +21/-8）：纯读操作移除全局锁 + try/except 兜底（ZK ChildWatcher 回调
#   并发修改 set 会抛 RuntimeError）+ Python 3 e.message 适配；已合入 vpc_agent 4.12.0 现网。
# 证据等级：接口/串行/60s/全局锁/修复内容/版本 = 实证；本文件为调用侧行为（修复不在本侧，调用侧不变）。
# 开源对照：数据库长事务持锁导致请求堆积（同类问题）；修复哲学 = 按操作语义分层（读无锁/写持锁）。
import time

from api.tvpc_handle import CTvpcThread
from common import tvpc_db_client as db
from common.agent_client import call_vpc_agent

VIF_READY_TIMEOUT_S = 60  # 材料实证：单次超时 60 秒（调用侧口径，改动不在本侧）


class CCheckTaskItemHandle(CTvpcThread):

    def __init__(self, ctx):
        super(CCheckTaskItemHandle, self).__init__(ctx)
        self.add_step(self.load_items)
        self.add_step(self.check_items)
        self.add_step(self.write_result)

    def load_items(self, item, ctx):
        ctx.items = db.query_list("t_migrate_task_item", where={"task_id": item["args"]["taskId"]})
        ctx.result["total"] = len(ctx.items)

    def check_items(self, item, ctx):
        """逐个 ENI 串行（材料实证）。为什么当初不改并发：检查有副作用吗？没有，但调用侧
        保守——**串行保证"检查顺序 = 迁移顺序"**，且 agent 侧当时有全局锁，并发只会让阻塞更糟。
        agent 4.12.0 移除全局锁后，这条串行链的墙钟仍受 60s 长尾支配——单次超时预算本身就是
        迁移窗口的敌人（这是"迁移卡顿"观感的一个结构性来源）。"""
        failed = []
        started = time.time()
        for it in ctx.items:
            if it["item_type"] != "ENI":
                continue
            try:
                resp = call_vpc_agent(
                    host=it["host_ip"], action="is_vif_ready",
                    args={"eniId": it["eni_id"], "vifId": it["vif_id"]},
                    timeout_s=VIF_READY_TIMEOUT_S)
                if not resp.get("is_ready"):
                    failed.append({"eniId": it["eni_id"], "errMsg": resp.get("err_msg", "")})
            except TimeoutError:
                # 超时 ≠ 未就绪：记"待重查"，不判死（保守策略；对账/重入会再检）。
                failed.append({"eniId": it["eni_id"], "reason": "timeout(60s)"})
        ctx.result["failed"] = failed
        ctx.result["elapsed_s"] = round(time.time() - started, 2)

    def write_result(self, item, ctx):
        db.insert(None, "t_migrate_check_result", {
            "task_id": item["args"]["taskId"],
            "failed_count": len(ctx.result["failed"]),
            "elapsed_s": ctx.result["elapsed_s"],
        })
