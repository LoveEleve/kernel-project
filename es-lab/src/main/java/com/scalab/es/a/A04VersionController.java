package com.scalab.es.a;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A04 版本控制（ExternalGte）——xhs 索引同步幂等的核心机制。
 *
 * <p>ExternalGte = 外部版本号必须 **大于等于** 当前版本才接受（否则 409 冲突）。
 * xhs 用「DB 记录的 updated_at 时间戳」作为外部版本号——旧数据（乱序到达/重复消费）被 ES 拒绝，
 * 天然实现"索引同步幂等 + 抗乱序"（对照 xhs NoteIndexSyncConsumer）。
 *
 * <p>实验：依次写 version=200 → 100（拒：更旧）→ 200（受：external_gte 允许相等——重复消息幂等重放）→ 300（受）
 */
@RestController
@RequestMapping("/a04")
public class A04VersionController {

    private final ElasticsearchClient es;
    private final List<String> log = new ArrayList<>();

    public A04VersionController(ElasticsearchClient es) {
        this.es = es;
    }

    @PostMapping("/index")
    public Map<String, Object> index(@RequestParam String id,
                                     @RequestParam long version,
                                     @RequestParam String title) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("attempt", "id=" + id + " version=" + version + " title=" + title);
        try {
            var resp = es.index(i -> i.index(A01IndexController.INDEX).id(id)
                    .document(Map.of("title", title, "version", version))
                    .version(version)
                    .versionType(VersionType.ExternalGte));
            r.put("result", "ACCEPTED（ES 版本=" + resp.version() + "）");
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("version_conflict")) {
                r.put("result", "REJECTED（409 version_conflict——外部版本 " + version + " 低于当前版本，旧数据被拒）");
            } else {
                r.put("result", "ERROR: " + e.getMessage());
            }
        }
        log.add(r.get("attempt") + " -> " + r.get("result"));
        return r;
    }

    @GetMapping("/get")
    public Object get(@RequestParam String id) throws Exception {
        var resp = es.get(g -> g.index(A01IndexController.INDEX).id(id), Map.class);
        return Map.of("found", resp.found(), "version", resp.version(),
                "source", resp.source() == null ? Map.of() : resp.source());
    }

    @GetMapping("/log")
    public Map<String, Object> log() {
        return Map.of("attempts", List.copyOf(log));
    }

    @DeleteMapping("/reset")
    public Map<String, Object> reset() {
        log.clear();
        return Map.of("result", "日志已清空（文档保留——用 version 继续实验）");
    }
}
