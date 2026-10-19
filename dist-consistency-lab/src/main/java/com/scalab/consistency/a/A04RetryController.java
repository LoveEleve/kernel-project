package com.scalab.consistency.a;

import com.scalab.consistency.core.RetryBackoffUtils;
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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A04 补偿重试——指数退避（封顶）+ 抖动 + 死信终态。
 *
 * <p>实验：
 * <ol>
 *   <li>{@code /a04/backoff?attempts=8} 看退避序列（base=30s / max=480s / jitter=±20%）</li>
 *   <li>{@code /a04/retry-flow?maxRetries=3&base=2&max=30} 模拟一条消息的重试链 → 死信</li>
 *   <li>{@code /a04/dead-letter} 看死信列表（终态，需人工/告警介入）</li>
 * </ol>
 */
@RestController
@RequestMapping("/a04")
public class A04RetryController {

    /** 死信列表（终态） */
    private final CopyOnWriteArrayList<String> deadLetters = new CopyOnWriteArrayList<>();

    /** 退避序列：base=30s / max=480s / jitter=±20%（对齐 xhs-project LocalMessageRetryJob 参数）。 */
    @GetMapping("/backoff")
    public Map<String, Object> backoff(@RequestParam(defaultValue = "8") int attempts) {
        long[] seq = RetryBackoffUtils.sequence(attempts, 30, 480, 0.2);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("base", "30s");
        r.put("max", "480s");
        r.put("jitter", "±20%");
        r.put("sequence(秒)", seq);
        r.put("note", "带抖动——多实例不会对齐共振；封顶后保持 480s±20%");
        return r;
    }

    /**
     * 模拟重试链：每次失败 → 计算退避 → 直到超过 maxRetries → 死信。
     * base/max 参数可调小便于演示（默认 2s/30s）。
     */
    @PostMapping("/retry-flow")
    public Map<String, Object> retryFlow(@RequestParam(defaultValue = "msg-1") String msgId,
                                         @RequestParam(defaultValue = "3") int maxRetries,
                                         @RequestParam(defaultValue = "2") long base,
                                         @RequestParam(defaultValue = "30") long max) {
        List<String> chain = new ArrayList<>();
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (attempt == maxRetries) {
                String dl = msgId + "（重试 " + attempt + " 次耗尽）";
                deadLetters.add(dl);
                chain.add("attempt " + attempt + ": 失败 → 超过上限 → 死信（终态，需告警/人工）");
            } else {
                long delay = RetryBackoffUtils.exponentialSeconds(attempt, base, max, 0.2);
                chain.add("attempt " + attempt + ": 失败 → " + delay + "s 后重试");
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("msgId", msgId);
        r.put("chain", chain);
        r.put("deadLetterCount", deadLetters.size());
        return r;
    }

    @GetMapping("/dead-letter")
    public Map<String, Object> deadLetter() {
        return Map.of("count", deadLetters.size(), "list", List.copyOf(deadLetters));
    }

    @DeleteMapping("/reset")
    public Map<String, Object> reset() {
        deadLetters.clear();
        return Map.of("result", "死信列表已清空");
    }
}
