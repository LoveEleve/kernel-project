# 【复现】logger.py —— 对应真实 nfv-proxy/logger.py
# 真实职责（23 §3.1）：日志封装——彩色控制台格式 + RotatingFileHandler 轮转；
#   异常钩子写日志；**critical 级别联动 qcloud_alarm 告警**（日志即告警的入口之一）。
# 证据等级：职责 = 实证；实现 = 教学等价。
import logging
import sys

LOG = logging.getLogger("nfv-proxy")
LOG.setLevel(logging.INFO)
_h = logging.StreamHandler(sys.stderr)
_h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s"))
LOG.addHandler(_h)
