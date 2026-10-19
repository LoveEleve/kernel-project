package com.scalab.es.a;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.GetResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A02 文档 CRUD + Bulk——近实时写入。
 *
 * <p>实验：
 * <ol>
 *   <li>{@code /a02/index?id=n1&title=...} 写一条文档 → {@code /a02/get?id=n1} 立刻读</li>
 *   <li>{@code /a02/bulk?count=100} 批量写 100 条（对比单条循环的性能差异）</li>
 *   <li>{@code /a02/count} 文档数</li>
 * </ol>
 * <p>注意：写入后默认 1s 才可被搜索到（refresh_interval=1s，近实时）——D4 详解。
 */
@RestController
@RequestMapping("/a02")
public class A02DocController {

    private final ElasticsearchClient es;

    public A02DocController(ElasticsearchClient es) {
        this.es = es;
    }

    @PostMapping("/index")
    public Map<String, Object> index(@RequestParam String id,
                                    @RequestParam String title,
                                    @RequestParam(defaultValue = "") String content,
                                    @RequestParam(defaultValue = "u1") String authorId,
                                    @RequestParam(defaultValue = "0") int likeCount) throws Exception {
        Map<String, Object> doc = Map.of(
                "title", title, "content", content,
                "authorId", authorId, "likeCount", likeCount,
                "updatedAt", System.currentTimeMillis());
        var resp = es.index(i -> i.index(A01IndexController.INDEX).id(id).document(doc));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("result", "已写入");
        r.put("id", resp.id());
        r.put("version", resp.version());
        return r;
    }

    @GetMapping("/get")
    public Object get(@RequestParam String id) throws Exception {
        GetResponse<Map> resp = es.get(g -> g.index(A01IndexController.INDEX).id(id), Map.class);
        return Map.of("found", resp.found(), "source", resp.source() == null ? Map.of() : resp.source());
    }

    /** 批量写：bulk 一次请求 vs 单条循环 count 次请求。 */
    @PostMapping("/bulk")
    public Map<String, Object> bulk(@RequestParam(defaultValue = "100") int count) throws Exception {
        var builder = new co.elastic.clients.elasticsearch.core.BulkRequest.Builder();
        for (int i = 1; i <= count; i++) {
            final int n = i;
            builder.operations(op -> op.index(idx -> idx
                    .index(A01IndexController.INDEX).id("bulk-" + n)
                    .document(Map.of("title", "批量笔记 " + n, "content", "content " + n,
                            "authorId", "u" + (n % 5), "likeCount", n, "updatedAt", System.currentTimeMillis()))));
        }
        long start = System.currentTimeMillis();
        BulkResponse resp = es.bulk(builder.build());
        long cost = System.currentTimeMillis() - start;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("count", count);
        r.put("errors", resp.errors());
        r.put("costMs", cost);
        r.put("note", "bulk 一次请求写 " + count + " 条——对比单条循环的 " + count + " 次网络往返");
        return r;
    }

    @GetMapping("/count")
    public Map<String, Object> count() throws Exception {
        // 先 refresh：ES 写入默认 1s 后才可被搜索/统计（近实时）——count 前主动刷新保证演示确定性
        es.indices().refresh(r -> r.index(A01IndexController.INDEX));
        long c = es.count(cq -> cq.index(A01IndexController.INDEX)).count();
        return Map.of("count", c, "note", "count 前已 refresh（近实时：默认写入后 1s 才可搜索）");
    }
}
