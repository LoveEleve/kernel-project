# 【复现】init_vpc_common.py —— 对应真实 nfv-proxy/init_vpc_common.py（CVpcSyn 公共基类，约 850 行）
# 真实职责（23 §3.3）：**所有 init_* 快照类的父类**——分片(fragments)/版本列表/CKV 写入/断点续传/
#   交集缺失判断等通用逻辑。
# 五个通用机制（本文件把它们钉成模板方法）：
#   ① 分片：大快照 → 多片 KV value（对齐 ckv_op.MAX_VALUE_BYTES）；
#   ② 版本列表：每个快照有版本号 + 片键清单（agent 按版本读一致代）；
#   ③ 断点续传：中途失败重启，从已完成片继续（checkpoint 落盘/DB）；
#   ④ **交集缺失判断**：当前数据 key 集合 vs 上次快照覆盖集合——
#      交集内 = 复用旧片；缺失 = 本次重做（这是"改造型全量"的核心：不盲目全刷）；
#   ⑤ value 数上限（子类可覆盖；材料原文：SubnetRule 的 **value 数上限 25000**——
#      "单快照 or 单片"的粒度材料未言明，本切片按"单片"实现并标注）。
# 证据等级：机制清单/25000 = 实证；粒度与实现 = 教学等价。
MAX_VALUES_PER_FRAGMENT = 25000  # value 数上限（材料口径：SubnetRule 25000）


class CVpcSyn(object):
    PREFIX = "ckv:unknown"           # 子类覆盖：CKV key 前缀（如 ckv:rtable）

    def __init__(self, vpc_id, region, zone, ckv, db_ro):
        self.vpc_id = vpc_id
        self.region = region
        self.zone = zone
        self.ckv = ckv                 # CCkv（ckv_op.py）
        self.db = db_ro                # vpcoss 只读 DB（config_oss_ro.py 的连接）

    # ---------------- 模板方法：一次完整快照同步 ----------------

    def sync(self):
        last = self.load_last_version()                 # ② 上次版本：{version, fragment_keys, covered_keys}
        todo = self.missing_intersection(last)          # ④ 交集缺失判断 → 本次要处理的分组
        if todo is None:
            LOG.info("[%s] vpc=%s up-to-date, skip", self.PREFIX, self.vpc_id)
            return
        version = self.next_version()
        resume = self.load_checkpoint(version)          # ③ 断点续传：上次写到第几片
        fragment_keys = self.ckv.write_fragments(
            self.prefix(), version, self.iter_values(todo), resume_from=resume or 0)
        self.save_checkpoint(version, len(fragment_keys))   # 全部写完才落完成态
        self.ckv.write_version_list(self.prefix(), version, fragment_keys)  # ②
        self.clear_checkpoint(version)
        LOG.info("[%s] vpc=%s snapshot v%d done (%d fragments)",
                 self.PREFIX, self.vpc_id, version, len(fragment_keys))

    # ---------------- 子类必须实现 ----------------

    def iter_values(self, todo):
        """产出快照 value 序列（真实：查 vpcoss RO DB + 按维度聚合）。"""
        raise NotImplementedError

    def current_keys(self):
        """当前数据的完整 key 集合（供交集缺失判断）。"""
        raise NotImplementedError

    # ---------------- ④ 交集缺失判断（通用实现） ----------------

    def missing_intersection(self, last):
        """交集缺失判断（"增量式全量"的关键）：
        - 上次无快照 → 全量 todo；
        - 有 → diff(当前 keys, 上次 covered_keys)：只在缺失集合上重做；
        - 交集完全覆盖 → None（跳过）。"""
        if not last:
            return self.current_keys()
        cur = set(self.current_keys())
        covered = set(last.get("covered_keys") or [])
        missing = cur - covered
        if not missing:
            return None
        # 交集内复用旧片、缺失部分重做——真实按"分组/片"粒度做这件事；
        # 切片内以 key 集合表达同一语义。
        return missing

    # ---------------- ①②③ 基类服务 ----------------

    def prefix(self):
        """快照 key 前缀：带 region/zone 便于多合一部署隔离。"""
        return "%s:%s:%s:%s" % (self.PREFIX, self.region, self.zone, self.vpc_id)

    def load_last_version(self):
        """读版本列表（真实存 CKV/DB 版本表）。"""
        return None

    def next_version(self):
        import time
        return int(time.time())

    def load_checkpoint(self, version):
        return 0

    def save_checkpoint(self, version, done_fragments):
        pass

    def clear_checkpoint(self, version):
        pass
