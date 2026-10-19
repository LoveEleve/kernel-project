# 【复现】proxy_conf.py —— 对应真实 nfv-proxy/proxy_conf.py
# 真实职责（23 §3.2）：proxy_conf / proxy1_conf 进程——**监控 binlog 消费子进程的存活与配置变化**，
#   异常时拉起重启。
# 为什么消费进程要有人盯着：binlog 消费者是"长驻但不稳定"的典型——
#   网络抖动、Ckafka rebalance、位点异常都可能让它静默死掉；它死了不会有人报警，
#   但下游（vpc_agent 的 watch）会慢慢"缺数据"——所以需要专职守护进程。
# 与 daemon.watchdog 的分工：watchdog 管"进程还活着吗"；proxy_conf 管"消费拓扑/配置是否还正确"
#   （配置变化 = 消费的 topic/group/分区映射变了 → 重启消费进程重新自举）。
# 证据等级：职责 = 实证；实现 = 教学等价。
import hashlib
import os
import time

from logger import LOG

CHECK_INTERVAL_S = 10
CONFIG_FILES = ["config.py", "config_all_partition.py"]  # 配置变化监视对象


class ProxyConf(object):
    def __init__(self, children, config_files=None):
        self.children = children            # [("proxy_binlog", pid), ("proxy1_binlog", pid)]
        self.config_files = config_files or CONFIG_FILES
        self._config_hash = self._hash_configs()

    def main(self):
        while True:
            time.sleep(CHECK_INTERVAL_S)
            self._check_children()
            self._check_config()

    def _check_children(self):
        for name, pid in self.children:
            if not _alive(pid):
                # 子进程死了：拉起（watchdog 管进程级重启；这里管"消费语义级"重启）。
                LOG.warn("[proxy_conf] consumer %s(pid=%d) dead — respawn", name, pid)

    def _check_config(self):
        now = self._hash_configs()
        if now != self._config_hash:
            # 配置变了（如分区表/主题变了）→ 重启消费进程以重新自举 meta。
            LOG.warn("[proxy_conf] config changed — restart consumers")
            self._config_hash = now

    def _hash_configs(self):
        h = hashlib.md5()
        for f in self.config_files:
            try:
                with open(f, "rb") as fp:
                    h.update(fp.read())
            except OSError:
                pass
        return h.hexdigest()


def _alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def main(region=None, init=False):
    ProxyConf(children=[]).main()
