# 【复现】common/tvpc_alarm.py —— 对应真实 sdn_oss/common/tvpc_alarm.py（告警发送封装 alarm()）
# 证据等级：文件与入口 = 实证；实现 = 教学等价。
import sys


class _Alarm(object):
    def send(self, title, detail=None):
        # 真实：走内部告警平台（barad/云监控系）；此处仅落 stderr 防静默。
        sys.stderr.write("[ALARM] %s %s\n" % (title, detail or {}))


alarm = _Alarm()
