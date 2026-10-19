# 【复现】common/log.py —— 对应真实 sdn_oss/common/log.py（日志门面）+ plog.py（底层 CLog）
# 真实职责：log_init / log_debug / log_info / log_warning / log_error —— 全服务统一日志入口。
# 证据等级：门面 API = 实证；实现 = 教学等价（真实底层是 C 风格 CLog + log.ini 配置）。
import sys
import time


class _Log(object):
    LEVELS = {"debug": 10, "info": 20, "warn": 30, "error": 40}
    _level = 20

    def _emit(self, level, fmt, *args):
        if self.LEVELS[level] < self._level:
            return
        msg = fmt % args if args else fmt
        sys.stderr.write("%s [%s] %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), level.upper(), msg))
        sys.stderr.flush()

    def debug(self, fmt, *args):
        self._emit("debug", fmt, *args)

    def info(self, fmt, *args):
        self._emit("info", fmt, *args)

    def warn(self, fmt, *args):
        self._emit("warn", fmt, *args)

    def error(self, fmt, *args):
        self._emit("error", fmt, *args)


LOG = _Log()
