# 【复现】check_ppid.py —— 对应真实 nfv-proxy/check_ppid.py
# 真实职责（23 §3.1）：ppid_monitor——定时检测父进程 PID 是否变为 1（父进程已退出），
#   是则**自杀，防孤儿业务进程**。
# 为什么需要它：daemon 双 fork 后，进程链是 main → watchdog → 业务进程；
#   若 watchdog 异常消失，业务进程会被 init(PID 1) 收养——表面"活着"，
#   但已经没人守护/没人重启它了。孤儿检测 = "我的守护者还在吗"。
# 证据等级：机制 = 实证；实现 = 教学等价（时间轮询版）。
import os
import time

from logger import LOG

CHECK_INTERVAL_S = 30


class PpidMonitor(object):
    def __init__(self, expected_ppid):
        self.expected_ppid = expected_ppid

    def main(self):
        while True:
            self.check_once()
            time.sleep(CHECK_INTERVAL_S)

    def check_once(self):
        ppid = os.getppid()
        if ppid == 1:
            LOG.error("[ppid] parent is init(1) — orphan detected")
            from daemon import suicide
            suicide(reason="orphan (ppid=1)")
        elif ppid != self.expected_ppid:
            # 守护者换人了（正常重启场景下也会出现）——记录观察即可。
            LOG.info("[ppid] parent changed: %d → %d", self.expected_ppid, ppid)
            self.expected_ppid = ppid


def main(expected_ppid=None, region=None, init=False):
    PpidMonitor(expected_ppid or os.getppid()).main()
