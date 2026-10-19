# 【复现】main.py —— 对应真实 nfv-proxy/main.py
# 真实职责（docs-by-topic/23 §3.1/§4.1）：总控入口 `./python3 main.py <module> [region] [init]`：
#   按模块名 fork 拉起对应常驻进程；支持 ALL_IN_ONE 多合一模式按地域批量 fork（EC 多地域共用 Ckafka/二代 ZK）；
#   所有常驻进程经 daemon.daemonize() + daemon.watchdog() 守护。
# 证据等级：module 映射表 / ALL_IN_ONE / 启动顺序 = 实证（§4.1 + bin/start.sh 清单）；
#   本文件为结构等价切片。
import os
import sys

import daemon

# module 名 → 实现模块（真实映射，§4.1 全表）。
MODULE_MAP = {
    "proxy_init_zk": "proxy_init_zk",           # 初始化分区 ZK 根路径（抢 INIT_ZK 锁）
    "proxy_conf": "proxy_conf",                  # 消费子进程守护
    "proxy1_conf": "proxy_conf",                 # 第二份守护（proxy1 系列）
    "proxy_binlog": "proxy_binlog",              # Ckafka binlog → 分区 ZK
    "proxy1_binlog": "proxy_binlog",             # 第二份 binlog 消费进程
    "proxy_init_vpc": "proxy_init_vpc",          # 存量 VPC 快照初始化（按机器序号切片多进程）
    "proxy_merge": "proxy_merge",                # 合并 binlog 分组 → 新快照 → CKV
    "proxy_merge_master": "proxy_merge_master",  # 合并调度主进程
    "proxy_lazymerge": "proxy_lazymerge",        # 惰性合并 6 类公共快照
    "proxy_cl5": "proxy_cl5",                    # CL5/北极星 RS 解析 → 分区 ZK
    "vpcgw_snapshot": "recovery_vpcgw_snapshot", # 进程粒度快照（可带 INIT 参数）
    "append_snapshot": "append_vpcgw_snapshot",  # 快照增量追加压缩
    "snapshot_differ": "tools_diff_snapshot_vpc",# 快照一致性比对
    "proxy_monitor": "proxy_monitor",            # 健康监控
}

# ALL_IN_ONE 全地域列表（真实：const.py 的 ALL_IN_ONE_REGION_LIST）。
ALL_IN_ONE_REGION_LIST = ["gz", "sh", "bj", "hk", "sg"]


def fork_module(module_name, region=None, init=False):
    """fork 拉起一个常驻进程；父进程退出前把 child pid 交给 watchdog 托管。"""
    module_path = MODULE_MAP.get(module_name)
    if module_path is None:
        raise SystemExit("unknown module: %s" % module_name)
    pid = os.fork()
    if pid == 0:
        # 子进程：交给模块自己的 main()（内部再 daemonize 双 fork 完成脱离终端）。
        mod = __import__(module_path)
        return mod.main(region=region, init=init)
    return pid


def main():
    if len(sys.argv) < 2:
        raise SystemExit("usage: ./python3 main.py <module> [region] [init]")
    module = sys.argv[1]
    region = sys.argv[2] if len(sys.argv) > 2 else None
    init = len(sys.argv) > 3 and sys.argv[3].upper() == "INIT"

    # ALL_IN_ONE：一个部署承载多个地域（EC 多地域共用 Ckafka/二代 ZK 的部署方案）。
    if region == "ALL_IN_ONE":
        children = [fork_module(module, region=r, init=init) for r in ALL_IN_ONE_REGION_LIST]
    else:
        children = [fork_module(module, region=region, init=init)]

    daemon.daemonize()          # 脱离终端
    daemon.watchdog(children)   # 守护：异常退出 → 重启；重启超阈值 → 星云告警
    sys.exit(0)


if __name__ == "__main__":
    main()
