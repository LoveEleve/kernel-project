# 【复现】common/cgi_help.py —— 对应真实 sdn_oss/common/cgi_help.py
# 真实职责：错误码常量（DB_ERROR、INVALID_PARAMETER 等）与通用帮助函数。
# 证据等级：错误码存在性 = 实证；具体数值 = 教学占位（真实为全服务统一错误码表）。
RET_OK = 0
RET_INVALID_PARAMETER = 4001
RET_REGION_MISMATCH = 4002
RET_RATE_LIMIT = 4003
RET_UNKNOWN_INTERFACE = 4004
RET_DB_ERROR = 5001
