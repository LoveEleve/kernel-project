package com.scalab.es.a;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A03 查询 DSL——match（分词）vs term（精确）vs bool（组合）。
 *
 * <p>实验（先跑 A02 bulk 造数据）：
 * <ol>
 *   <li>{@code /a03/match?q=批量}——match 走分词，能匹配"批量笔记"</li>
 *   <li>{@code /a03/term?authorId=u1}——term 精确匹配 keyword 字段</li>
 *   <li>{@code /a03/bool?q=笔记&minLikes=50}——bool（must + range）组合</li>
 *   <li>{@code /a03/page?from=0&size=5}——分页 + 排序（注意 from+size 深分页限制）</li>
 * </ol>
 */
@RestController
@RequestMapping("/a03")
public class A03QueryController {

    private final ElasticsearchClient es;

    public A03QueryController(ElasticsearchClient es) {
        this.es = es;
    }

    @GetMapping("/match")
    public Map<String, Object> match(@RequestParam String q,
                                     @RequestParam(defaultValue = "title") String field) throws Exception {
        SearchResponse<Map> resp = es.search(s -> s.index(A01IndexController.INDEX)
                        .query(query -> query.match(m -> m.field(field).query(q)))
                        .size(5),
                Map.class);
        return result(resp);
    }

    @GetMapping("/term")
    public Map<String, Object> term(@RequestParam String authorId) throws Exception {
        SearchResponse<Map> resp = es.search(s -> s.index(A01IndexController.INDEX)
                        .query(query -> query.term(t -> t.field("authorId").value(authorId)))
                        .size(5),
                Map.class);
        return result(resp);
    }

    @GetMapping("/bool")
    public Map<String, Object> bool(@RequestParam String q,
                                    @RequestParam(defaultValue = "0") int minLikes) throws Exception {
        SearchResponse<Map> resp = es.search(s -> s.index(A01IndexController.INDEX)
                        .query(query -> query.bool(b -> b
                                .must(m -> m.match(mm -> mm.field("title").query(q)))
                                .filter(f -> f.range(r -> r.field("likeCount").gte(co.elastic.clients.json.JsonData.of(minLikes))))))
                        .size(5),
                Map.class);
        return result(resp);
    }

    @GetMapping("/page")
    public Map<String, Object> page(@RequestParam(defaultValue = "0") int from,
                                    @RequestParam(defaultValue = "5") int size) throws Exception {
        SearchResponse<Map> resp = es.search(s -> s.index(A01IndexController.INDEX)
                        .from(from).size(size)
                        .sort(so -> so.field(f -> f.field("likeCount").order(co.elastic.clients.elasticsearch._types.SortOrder.Desc))),
                Map.class);
        Map<String, Object> r = result(resp);
        r.put("note", "from+size 深分页有窗口上限（index.max_result_window=10000）——深分页用 search_after（D3）");
        return r;
    }

    private Map<String, Object> result(SearchResponse<Map> resp) {
        List<String> titles = resp.hits().hits().stream()
                .map(h -> String.valueOf(h.source() == null ? "" : h.source().get("title")))
                .toList();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", resp.hits().total() == null ? -1 : resp.hits().total().value());
        r.put("returned", resp.hits().hits().size());
        r.put("titles", titles);
        return r;
    }
}
