# 【复现】qcloud_alarm.py —— 对应真实 nfv-proxy/qcloud_alarm.py
# 真实职责：星云告警上报 alarm() 向 api.xingyun 告警接口 POST（带 SECURITY_CODE、主机 IP）。
# 证据等级：接口形态 = 实证；实现 = 教学等价（落 stderr 防静默）。
import sys


def alarm(title, detail=None):
    sys.stderr.write("[XINGYUN-ALARM] %s %s\n" % (title, detail or {}))
