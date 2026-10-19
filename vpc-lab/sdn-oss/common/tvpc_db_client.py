# 【复现】common/tvpc_db_client.py —— 对应真实 sdn_oss/common/tvpc_db_client.py（全服务最核心数据访问入口）
# 真实职责：主库 MySQL 客户端 CTvpcDbClient + 查询条件 CQueryCond；服务 **585 张业务表**
#   （VPC控制面.md:3922 "585 张 MySQL 表 (common/db_tvpc/) + 25 张事务 outbox 表"）。
# 本轮 review 修正：分库数量口径——另有 10 个专用/扩展客户端（db_client2 通用第二版 +
#   state/task/transaction/trans_id/log/charge/ipv6/detect_state/cz_br 九个专用库），
#   原写"12 个"不准确。
# 证据等级：客户端与 CQueryCond、分库清单、585/25 表数 = 实证；本切片 API 形态 = 教学等价。
# 开源对照：SQLAlchemy Core 的 query builder；Django ORM 的 QuerySet——同类问题（条件拼装安全）。
import threading


class CQueryCond(object):
    """查询条件构造器：把 dict 条件拼装为参数化 WHERE（防注入 + 分表路由键）。"""

    def __init__(self, where=None):
        self._where = dict(where or {})
        self._order = None
        self._limit = None

    def where(self, **kw):
        self._where.update(kw)
        return self

    def order_by(self, col, desc=False):
        self._order = (col, desc)
        return self

    def limit(self, n):
        self._limit = n
        return self

    def sql_fragment(self):
        parts = ["%s = %%s" % k for k in self._where]
        sql = (" WHERE " + " AND ".join(parts)) if parts else ""
        if self._order:
            col, desc = self._order
            sql += " ORDER BY %s %s" % (col, "DESC" if desc else "ASC")
        if self._limit:
            sql += " LIMIT %d" % self._limit
        return sql, list(self._where.values())


class CTvpcDbClient(object):
    """主库客户端。真实实现要点：
    - 每进程建连接池（uWSGI preload/fork 之后各 worker 独立连接——fork 安全）；
    - 585 张表访问类由 gen_tvpc_db_table_py.py 从 DDL 生成（生成类通常不手改）。"""

    def __init__(self, dsn):
        self.dsn = dsn
        self._local = threading.local()

    def _conn(self):
        if not hasattr(self._local, "conn"):
            self._local.conn = _RawConn(self.dsn)
        return self._local.conn

    # ---------------- 简单读写（被 Handle 直接使用） ----------------

    def query_one(self, table, where=None):
        sql, params = CQueryCond(where).limit(1).sql_fragment()
        return self._conn().query_one("SELECT * FROM %s%s" % (table, sql), params)

    def query_list(self, table, where=None):
        sql, params = CQueryCond(where).sql_fragment()
        return self._conn().query_list("SELECT * FROM %s%s" % (table, sql), params)

    def query_count(self, table, where=None):
        sql, params = CQueryCond(where).sql_fragment()
        row = self._conn().query_one("SELECT COUNT(*) AS c FROM %s%s" % (table, sql), params)
        return row["c"] if row else 0

    def insert(self, conn, table, row):
        (conn or self._conn()).insert(table, row)

    def delete(self, table, where=None):
        sql, params = CQueryCond(where).sql_fragment()
        self._conn().execute("DELETE FROM %s%s" % (table, sql), params)

    def execute(self, sql, params=()):
        self._conn().execute(sql, params)


class _Transaction(object):
    def __enter__(self):
        self.conn = _DbClientSingleton.get()._conn()
        self.conn.begin()
        return self.conn

    def __exit__(self, exc_type, exc, tb):
        if exc_type is None:
            self.conn.commit()
        else:
            self.conn.rollback()  # 业务异常 → 整事务回滚；Outbox 登记一并回滚（同事务）
        return False


def transaction():
    """用法：with db.transaction() as conn: ...（业务表 + Outbox 原子提交）"""
    return _Transaction()


class _DbClientSingleton(object):
    _client = CTvpcDbClient("tvpc_main_dsn")

    @classmethod
    def get(cls):
        return cls._client


# ---------------- 模块级快捷函数（真实实现里 Handle 直接 import 使用） ----------------
def query_one(table, where=None):
    return _DbClientSingleton.get().query_one(table, where)


def query_list(table, where=None):
    return _DbClientSingleton.get().query_list(table, where)


def query_count(table, where=None):
    return _DbClientSingleton.get().query_count(table, where)


def insert(conn, table, row):
    _DbClientSingleton.get().insert(conn, table, row)


def delete(table, where=None):
    _DbClientSingleton.get().delete(table, where)


def execute(sql, params=()):
    _DbClientSingleton.get().execute(sql, params)


class _RawConn(object):
    """真实为 MySQLdb 连接；本切片为占位（跑起来需要真实 MySQL / 或替换为 sqlite 适配）。"""

    def __init__(self, dsn):
        self.dsn = dsn

    def begin(self):
        pass

    def commit(self):
        pass

    def rollback(self):
        pass

    def execute(self, sql, params=()):
        pass

    def insert(self, table, row):
        pass

    def query_one(self, sql, params=()):
        return None

    def query_list(self, sql, params=()):
        return []
