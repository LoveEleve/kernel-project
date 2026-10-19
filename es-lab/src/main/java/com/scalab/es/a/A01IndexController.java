package com.scalab.es.a;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.AnalyzeResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A01 索引与 Mapping——倒排索引的入口。
 *
 * <p>实验：
 * <ol>
 *   <li>{@code /a01/create} 建 notes 索引（mapping：text/keyword/integer）</li>
 *   <li>{@code /a01/analyze?text=小红书种草笔记} 看标准分词器把文本切成什么 token（倒排的"词项"）</li>
 *   <li>{@code /a01/mapping} 看字段类型（text=分词 / keyword=不分词精确匹配）</li>
 * </ol>
 */
@RestController
@RequestMapping("/a01")
public class A01IndexController {

    public static final String INDEX = "notes";

    private final ElasticsearchClient es;

    public A01IndexController(ElasticsearchClient es) {
        this.es = es;
    }

    @PostMapping("/create")
    public Map<String, Object> create() throws Exception {
        boolean exists = es.indices().exists(e -> e.index(INDEX)).value();
        if (exists) {
            es.indices().delete(d -> d.index(INDEX));
        }
        es.indices().create(c -> c.index(INDEX).mappings(m -> m
                .properties("title", p -> p.text(t -> t
                        .analyzer("standard")
                        .fields("keyword", f -> f.keyword(k -> k.ignoreAbove(256)))))
                .properties("content", p -> p.text(t -> t.analyzer("standard")))
                .properties("authorId", p -> p.keyword(k -> k))
                .properties("likeCount", p -> p.integer(i -> i))
                .properties("updatedAt", p -> p.long_(l -> l))));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("result", "索引 " + INDEX + " 已创建（mapping：title=text+keyword / authorId=keyword / likeCount=integer）");
        return r;
    }

    /** 分词实验：标准分词器把文本切成什么 token（中文默认按字切——生产用 IK 分词器，见 D2）。 */
    @GetMapping("/analyze")
    public Map<String, Object> analyze(@RequestParam String text) throws Exception {
        AnalyzeResponse resp = es.indices().analyze(a -> a.analyzer("standard").text(text));
        List<String> tokens = resp.tokens().stream().map(t -> t.token()).toList();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("text", text);
        r.put("tokens", tokens);
        r.put("note", "standard 分词器对中文按单字切分；生产（xhs）用 IK 分词器（ik_smart/ik_max_word）");
        return r;
    }

    @GetMapping("/mapping")
    public Object mapping() throws Exception {
        return es.indices().getMapping(g -> g.index(INDEX)).get(INDEX).mappings().properties();
    }

    @DeleteMapping("/index")
    public Map<String, Object> delete() throws Exception {
        es.indices().delete(d -> d.index(INDEX));
        return Map.of("result", "索引 " + INDEX + " 已删除");
    }
}
