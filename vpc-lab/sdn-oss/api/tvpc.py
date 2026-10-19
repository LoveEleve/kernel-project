# 【复现】api/tvpc.py —— 对应真实 sdn_oss/api/tvpc.py
# 真实职责（docs-by-topic 24 §3.1.1 + VPC控制面.md:12746/13310）：
#   uwsgi 入口模块（uwsgi 配置 `module=tvpc`）；import 全部 Handle 类构建 interfaceName → Handler
#   注册表；可调用对象 application()（**真实文件名 api/tvpc.py:351**）；预加载核心高频接口。
# 组织原则（材料金句）：**一个接口名 = 一个 Handle 类 = 一个文件**
#   （如 AddVpc → add_vpc_handle.py::CAddVpcHandle）——文件名即接口语义。
# 证据等级：注册表机制/WSGI 入口(:351)/预加载 = 实证；本文件为结构等价切片（真实为 2000+ 接口）。
# 开源对照：WSGI（PEP 3333）；Flask/Django 的 url_map——区别：这里是"接口名驱动"而非"路径驱动"。
import importlib
import json
import sys

from api.cgi_base_tvpc import CCgiBaseTvpc
from common.cgi_help import RET_INTERNAL_ERROR

# interfaceName → Handler 类 的全局注册表。
# 真实实现：启动时扫描 api/ 下全部 *_handle.py（约 2020 个）自动注册；本切片显式列出。
HANDLE_MODULES = [
    ("TVPC.AddRtbRoute", "api.add_rtb_route_handle", "CAddRtbRouteHandle"),
    ("TVPC.CheckMigrateTaskItem", "api.CheckMigrate.check_task_item_handle", "CCheckTaskItemHandle"),
]

PRELOAD = ["TVPC.AddRtbRoute"]  # 预加载：避免首次请求的 import 冷启动

REGISTRY = {}


def build_registry():
    for interface_name, module_path, class_name in HANDLE_MODULES:
        module = importlib.import_module(module_path)
        REGISTRY[interface_name] = getattr(module, class_name)


def application(environ, start_response):
    """WSGI 入口（真实为 api/tvpc.py:351 的 application()）。"""
    try:
        body_len = int(environ.get("CONTENT_LENGTH") or 0)
        raw = environ["wsgi.input"].read(body_len)
        req = json.loads(raw)
        cgi = CCgiBaseTvpc(environ)
        ret = cgi.execute(req, REGISTRY)
    except ValueError as e:  # 信封本身不合法（非 JSON 等）
        ret = {"retCode": RET_INTERNAL_ERROR, "retMsg": "bad envelope: %s" % e}
    start_response("200 OK", [("Content-Type", "application/json")])
    return [json.dumps(ret).encode("utf-8")]


build_registry()
for name in PRELOAD:
    _ = REGISTRY[name]
sys.stderr.write("tvpc.py loaded: %d handles registered\n" % len(REGISTRY))
