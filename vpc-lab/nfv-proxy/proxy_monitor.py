# 【复现】proxy_monitor.py —— 对应真实 nfv-proxy/proxy_monitor.py（+ monitor_scan.py）
# 真实职责（23 §3.1/§3.2）：
#   ① proxy_monitor：健康检查——校验各 proxy 进程存活（get_process_id_by_name 正则查 PID）、
#      TCP 连接数（get_tcp_connections_by_pid 按 PID 枚举）、DB 可达性；异常**写 ZK 并告警**；
#   ② monitor_scan（配合进程）：扫描 `scan_` 前缀进程数量，与 config_all_partition 的**期望分区数**比对，
#      异常告警——这是"分区覆盖完整性"检查：少一个分区进程 = 一片地域的配置没人同步。
# 为什么写 ZK：健康状态暴露在 ZK 上 → 跳板/运维工具/其他组件都能读（监控也是一种控制面数据）。
# 证据等级：检查项/scan_ 比对 = 实证；实现 = 教学等价。
import os
import re
import time

from logger import LOG
from qcloud_alarm import alarm

CHECK_INTERVAL_S = 60

# 期望的常驻进程（示意清单——真实按主机角色拉起，名单见 bin/start.sh：
# 先 init_zk/proxy_conf/proxy1_conf/init_vpc/binlog/proxy1_binlog/cl5，
# 再按 tcs 主机或 CPU≥8 核加 merge_master/merge/lazymerge/monitor/differ/vpcgw_snapshot）。
EXPECTED_PROCS = [
    "proxy_binlog", "proxy1_binlog", "proxy_conf", "proxy1_conf",
    "proxy_merge_master", "proxy_merge", "proxy_lazymerge",
    "proxy_monitor", "vpcgw_snapshot",
]

# 分区期望值（示意——真实来自 config_all_partition.py 静态表，数量未知）。
EXPECTED_SCAN_PARTITIONS = 5


class ProxyMonitor(object):
    def main(self):
        while True:
            report = {}
            report["procs"] = self.check_procs()
            report["tcp"] = self.check_tcp()
            report["db"] = self.check_db()
            report["scan_partitions"] = self.check_scan_partitions()
            self.report_to_zk(report)
            time.sleep(CHECK_INTERVAL_S)

    # ---------------- 检查项 ----------------

    def check_procs(self):
        """按进程名正则查 PID（真实 sys_utils.get_process_id_by_name）。缺失 → 告警。"""
        missing = []
        for name in EXPECTED_PROCS:
            if not _pid_of(name):
                missing.append(name)
        if missing:
            alarm("nfv-proxy procs missing", detail={"missing": missing})
        return {"expected": len(EXPECTED_PROCS), "missing": missing}

    def check_tcp(self):
        """按 PID 枚举 TCP 连接（真实 sys_utils.get_tcp_connections_by_pid）；
        连接数异常（Kafka/ZK 连接掉光）→ 告警。"""
        return {"connections": 0}

    def check_db(self):
        """DB 可达性（SELECT 1 / mysql_ping.PingThread 保活线程是另一层）。"""
        return {"reachable": True}

    def check_scan_partitions(self):
        """monitor_scan：`scan_` 前缀进程数 vs 期望分区数——分区覆盖完整性。"""
        running = len(_pids_of_prefix("scan_"))
        if running != EXPECTED_SCAN_PARTITIONS:
            alarm("scan partition coverage mismatch",
                  detail={"running": running, "expected": EXPECTED_SCAN_PARTITIONS})
        return {"running": running, "expected": EXPECTED_SCAN_PARTITIONS}

    def report_to_zk(self, report):
        LOG.info("[monitor] report → ZK: %s", report)
        # 真实：写 ZK 健康节点（如 /PROXY/{region}/{zone}/health/{proc}）。


def _pid_of(name):
    return None


def _pids_of_prefix(prefix):
    return []


def main(region=None, init=False):
    ProxyMonitor().main()
