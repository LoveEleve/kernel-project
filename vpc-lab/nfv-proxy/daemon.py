# 【复现】daemon.py —— 对应真实 nfv-proxy/daemon.py
# 真实职责（docs-by-topic/23 §3.1）：守护进程公共库——daemonize 双 fork、watchdog 子进程守护与重启告警、
#   信号处理、条件变量 cond_wait/notify、suicide 随机自杀退出。
# 关键设计（材料实证 + 08 文档教训）：
#   - **suicide 带随机延迟**：ZK 抖动的历史血案里，"同时死、同时起"才是灾难放大器——
#     随机化重启时刻，避免惊群（thundering herd）；
#   - watchdog 重启有阈值：超阈值走告警（星云），而不是无限重启掩盖问题。
# 证据等级：机制清单 = 实证；随机延迟/阈值逻辑 = 由事故推导（教学等价）。
import os
import random
import signal
import sys
import threading
import time

from logger import LOG  # 真实 logger.py：彩色控制台 + 轮转文件 + critical 联动 qcloud_alarm

_shutdown = threading.Event()
_cond = threading.Condition()


# ---------------- 双 fork 守护化 ----------------

def daemonize():
    """经典双 fork：第一次 fork 让父进程退出（shell 认为命令已结束）；
    setsid 脱离会话组；第二次 fork 确保不是会话首进程（彻底与终端解耦）；重定向 stdio。"""
    if os.fork() > 0:
        os._exit(0)
    os.setsid()
    if os.fork() > 0:
        os._exit(0)
    fd = os.open("/dev/null", os.O_RDWR)
    for stream in (0, 1, 2):
        os.dup2(fd, stream)
    os.close(fd)
    signal.signal(signal.SIGTERM, _on_sigterm)
    signal.signal(signal.SIGCHLD, _on_sigchld)


def _on_sigterm(signum, frame):
    """优雅停机：置标志，业务线程自查后退出（而非直接 kill）。"""
    LOG.info("[daemon] SIGTERM received, graceful shutdown")
    _shutdown.set()
    with _cond:
        _cond.notify_all()


def _on_sigchld(signum, frame):
    try:
        while os.waitpid(-1, os.WNOHANG)[0] > 0:
            pass
    except OSError:
        pass


# ---------------- watchdog ----------------

def watchdog(children, restart_window_s=300, restart_threshold=10):
    """守护子进程集合：死了就重启；**重启过频是症状不是常态** → 触发星云告警。
    （窗口/阈值为示意——材料只给"重启告警"机制，未给具体数值。）"""
    from qcloud_alarm import alarm
    while not _shutdown.is_set():
        time.sleep(5)
        for i, pid in enumerate(children):
            if _alive(pid):
                continue
            LOG.warn("[watchdog] child %d (pid=%d) dead, restarting", i, pid)
            _restart_counts = getattr(watchdog, "_counts", [0] * len(children))
            _restart_counts[i] += 1
            watchdog._counts = _restart_counts
            if _restart_counts[i] > restart_threshold:
                alarm("nfv-proxy child restart storm (idx=%d)" % i,
                      detail={"count": _restart_counts[i]})
            children[i] = os.fork()
            if children[i] == 0:
                return  # 子进程路径：由调用方（main.py fork 分支）继续执行业务


def _alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


# ---------------- suicide ----------------

def suicide(reason="", delay_max_s=10):
    """随机延迟自杀（**防惊群**）：多进程同时检测到同一异常时，
    随机化退出时刻 → watchdog 重启也错峰 → 不给 ZK/DB 第二波冲击。
    （延迟窗口为示意——材料只给"随机自杀"机制，未给窗口值。）"""
    delay = random.uniform(0, delay_max_s)
    LOG.error("[daemon] suicide in %.1fs: %s", delay, reason)
    time.sleep(delay)
    os._exit(1)


# ---------------- 条件变量工具 ----------------

def cond_wait(timeout=None):
    with _cond:
        _cond.wait(timeout)


def cond_notify():
    with _cond:
        _cond.notify_all()


def stopping():
    return _shutdown.is_set()
