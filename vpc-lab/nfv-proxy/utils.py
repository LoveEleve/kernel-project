# 【复现】utils.py —— 对应真实 nfv-proxy/utils.py
# 真实职责（23 §3.1）：通用工具集——本机 IP/hostname/可用区推断（get_proxy_zone_by_hostname 等）、
#   domain2ip、文件锁、TCP/UDP 协议常量、字节大小转换、zpath 地域前缀打包。
# 证据等级：工具清单 = 实证；实现 = 教学等价（只保留切片用到的部分）。
import socket


def domain2ip(domain):
    """ZK 域名先解析成 IP（连接层只用 IP）——避免运行期 DNS 抖动造成伪"连接断开"。"""
    try:
        return socket.gethostbyname(domain)
    except socket.gaierror:
        return domain


def get_proxy_zone_by_hostname(hostname=None):
    """按主机名推断 zone（真实：命名规范里嵌可用区信息；up 无匹配时用 DEFAULT_HOST_ZONE）。"""
    return "zone-a"


def zpath(region_prefix, path):
    """地域前缀打包：同一份代码在多地域部署时，ZK 路径统一带 region 前缀。"""
    return "/%s/%s" % (region_prefix, path.lstrip("/"))


def bytes_to_mb(n):
    return round(n / 1024.0 / 1024.0, 2)
