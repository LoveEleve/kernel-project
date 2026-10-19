# 【复现】file_differ.py —— 对应真实 nfv-proxy/file_differ.py
# 真实职责（23 §3.1）：Differ 类——借助 sort/comm **外部命令**对两个大数据文件求差集/交集，
#   供 snapshot_differ（tools_diff_snapshot_vpc）比对快照。
# 工程味（为什么不用内存 set）：
#   快照文件可达千万行/GB 级——Python set 一步就 OOM；
#   `sort` 外部命令 + `comm` 归并比较是"操作系统替你流式跑"的经典手艺：
#   排序用磁盘/流式，比较也流式，内存占用恒定。
# 证据等级：实现方式（sort/comm）= 实证；封装 = 教学等价。
import subprocess
import tempfile

from logger import LOG


class Differ(object):
    def diff(self, file_a, file_b):
        """返回 (only_in_a, only_in_b, both)：差集 / 交集（逐行比较，流式）。"""
        a_sorted = self._sorted(file_a)
        b_sorted = self._sorted(file_b)
        only_a = self._comm(a_sorted, b_sorted, "-23")
        only_b = self._comm(a_sorted, b_sorted, "-13")
        both = self._comm(a_sorted, b_sorted, "-12")
        return only_a, only_b, both

    def compare_counts(self, file_a, file_b):
        """供 snapshot_differ 快速判定两代快照是否一致（行数 + 摘要）。"""
        only_a, only_b, both = self.diff(file_a, file_b)
        report = {"only_a": len(only_a), "only_b": len(only_b), "both": len(both)}
        if report["only_a"] or report["only_b"]:
            LOG.warn("[differ] snapshot mismatch: %s", report)
        return report

    # ---------------- 外部命令封装 ----------------

    def _sorted(self, path):
        """sort -o：原地排序（真实直接对快照导出文件排序）。"""
        out = path + ".sorted"
        subprocess.run(["sort", "-o", out, path], check=True)
        return out

    def _comm(self, a, b, opt):
        """comm -23/-13/-12：流式归并比较。"""
        try:
            r = subprocess.run(["comm", opt, a, b], check=True, capture_output=True, text=True)
            return r.stdout.splitlines()
        except FileNotFoundError:
            # 无 comm 环境（教学机）→ 退化为内存 set（仅小文件可用，标注差异）。
            return self._fallback(a, b, opt)

    def _fallback(self, a, b, opt):
        with open(a, encoding="utf-8") as fa, open(b, encoding="utf-8") as fb:
            sa = set(fa)
            sb = set(fb)
        if opt == "-23":
            return list(sa - sb)
        if opt == "-13":
            return list(sb - sa)
        return list(sa & sb)
