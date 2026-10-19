# 【复现】ckv_op.py —— 对应真实 nfv-proxy/ckv_op.py（CCkv）
# 真实职责（23 §3.1）：CKV（redis 协议 KV）快照写入封装——key 分片 fragments、版本 key 管理、
#   单 value 大小控制、经北极星取 CKV 实例。
# 核心机制：**大快照 → 多个 KV value（fragments）**。
#   为什么分片：CKV 是 redis 协议——单 value 过大 = 大 key（阻塞主线程/超时/迁移困难）；
#   分片后：写入可并发、失败可单片重试、agent 可按需拉取需要的片。
#   版本 key：快照版本列表登记（agent 端先读版本，再按版本拉片——保证读到的是一致代）。
# 证据等级：分片/版本/大小控制/北极星取实例 = 实证；实现 = 教学等价。
from logger import LOG

# 单 value 上限（推导口径：redis 协议友好大小；真实有"单 value 大小控制"逻辑）。
MAX_VALUE_BYTES = 512 * 1024


class CCkv(object):
    def __init__(self, ckv_addr):
        self.addr = ckv_addr   # 经北极星解析的实例地址

    # ---------------- 版本管理 ----------------

    def write_version_list(self, prefix, version, fragment_keys):
        """版本 key：`{prefix}:version` → 版本号 + 本版全部片键。
        agent 先读它，再按片键拉数据——"先版本后数据"是一致代读取的握手。"""
        key = "%s:version" % prefix
        self._set(key, "%d|%s" % (version, ",".join(fragment_keys)))
        LOG.info("[ckv] version committed: %s=%d (%d fragments)", key, version, len(fragment_keys))

    # ---------------- 分片写入 ----------------

    def write_fragments(self, prefix, version, values_iter, resume_from=0):
        """把大列表切成片写入；resume_from 支持断点续传（跳过已确认的片）。
        返回：已写入的片键列表。"""
        fragment_keys = []
        buf, buf_idx = [], resume_from
        for value in values_iter:
            buf.append(value)
            if _size_of(buf) >= MAX_VALUE_BYTES:
                key = "%s:%d:%d" % (prefix, version, buf_idx)
                self._set(key, _serialize(buf))     # 真实：protobuf（vpc_ckv.proto）序列化
                fragment_keys.append(key)
                buf, buf_idx = [], buf_idx + 1
        if buf:
            key = "%s:%d:%d" % (prefix, version, buf_idx)
            self._set(key, _serialize(buf))
            fragment_keys.append(key)
        return fragment_keys

    def _set(self, key, value):
        # 真实：redis-py 客户端 set；另经北极星 UpdateCallResult 回报调用结果。
        LOG.info("[ckv] SET %s (%d B)", key, len(str(value)))


def _size_of(buf):
    return sum(len(str(x)) for x in buf)


def _serialize(values):
    return values
