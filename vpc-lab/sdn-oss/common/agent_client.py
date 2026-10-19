# 【复现】common/agent_client.py —— 对应真实 sdn_oss 对 agent 的访问通道（api/agent_op.py / detect_agent.py / tcp_client_handle.py 的统一抽取）
# 真实职责：sdn_oss → vpc_agent 的调用封装（探测、迁移检查、下发确认）。
# 证据等级：调用方存在性（agent_op 等文件）= 实证；本客户端 API = 教学等价。
# 注意：真实为**多通道混合**——探测走 TCP 长连接（tcp_client_handle），
#   管理调用走 HTTP 内网接口；本切片统一为一个 call 入口，注释保留通道差异。
def call_vpc_agent(host, action, args, timeout_s=5):
    """调用母机 agent。异常语义（调用方必须处理）：
    - ConnectionError：agent 不可达（母机宕机/agent 未起）——与"检查不通过"严格区分；
    - TimeoutError：超时——**不能翻译成 False**，必须区分"未就绪"和"没问到"。"""
    raise TimeoutError("stub: no agent at %s" % host)
