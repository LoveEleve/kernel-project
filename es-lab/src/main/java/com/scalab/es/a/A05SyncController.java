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
 * A05 索引同步模式——复刻 xhs 的"DB 变更 → 索引更新 + ExternalGte 抗乱序"链路。
 *
 * <p>xhs 场景（NoteIndexSyncConsumer）：Canal/MQ 把 DB 变更送到搜索服务，
 * 消息可能乱序/重复——以 `updated_at` 为外部版本号，ExternalGte 保证**旧版本永远覆盖不了新版本**。
 *
 * <p>实验：
 * <ol>
 *   <li>{@code /a05/db-update?id=n100&title=A&updatedAt=1000} 第一次同步（接受）</li>
 *   <li>{@code /a05/db-update?id=n100&title=旧数据&updatedAt=900} 乱序消息（拒绝——索引仍是 A）</li>
 *   <li>{@code /a05/db-update?id=n100&title=B&updatedAt=1100} 新变更（接受）</li>
 *   <li>{@code /a05/db-update?...&updatedAt=1100} 重复消息（接受——external_gte 允许相等，幂等重放）</li>
 *   <li>{@code /a05/doc?id=n100} 验证最终状态=B</li>
 *   <li>{@code /a05/scripted?id=n200} 一键跑完整脚本链</li>
 * </ol>
 */
@RestController
@RequestMapping("/a05")
public class A05SyncController {

    private final ElasticsearchClient es;
    private final List<String> log = new ArrayList<>();

    public A05SyncController(ElasticsearchClient es) {
        this.es = es;
    }

    /** 模拟"DB 变更事件"到达搜索服务并同步索引（version=updatedAt）。 */
    @PostMapping("/db-update")
    public Map<String, Object> dbUpdate(@RequestParam String id,
                                        @RequestParam String title,
                                        @RequestParam long updatedAt) {
        Map<String, Object> r = new LinkedHashMap<>();
        String attempt = "sync id=" + id + " title=" + title + " updatedAt=" + updatedAt;
        try {
            var resp = es.index(i -> i.index(A01IndexController.INDEX).id(id)
                    .document(Map.of("title", title, "content", "", "authorId", "u1",
                            "likeCount", 0, "updatedAt", updatedAt))
                    .version(updatedAt)
                    .versionType(VersionType.ExternalGte));
            r.put("result", "ACCEPTED（索引已更新，ES 版本=" + resp.version() + "）");
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("version_conflict")) {
                r.put("result", "REJECTED（更旧版本——索引保持较新值，xhs 抗乱序生效）");
            } else {
                r.put("result", "ERROR: " + e.getMessage());
            }
        }
        log.add(attempt + " -> " + r.get("result"));
        r.put("attempt", attempt);
        return r;
    }

    /** 一键脚本链：1000（受）→ 900（拒：更旧）→ 1100（受）→ 重复 1100（受：相等允许，幂等重放）。 */
    @PostMapping("/scripted")
    public Map<String, Object> scripted(@RequestParam(defaultValue = "n200") String id) {
        List<Map<String, Object>> chain = new ArrayList<>();
        chain.add(dbUpdate(id, "初始标题", 1000));
        chain.add(dbUpdate(id, "乱序旧数据", 900));
        chain.add(dbUpdate(id, "最新标题", 1100));
        chain.add(dbUpdate(id, "重复的最新(同版本重放)", 1100));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("chain", chain);
        return r;
    }

    @GetMapping("/doc")
    public Object doc(@RequestParam String id) throws Exception {
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
        return Map.of("result", "日志已清空");
    }
}
