package com.scalab.micrometer.a;

import io.micrometer.core.annotation.Counted;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-08 @Counted 注解验证——对照 D4 §四 @Counted + CountedAspect。
 *
 * 验证点：
 * 1. {@code @Counted} 注解自动记录方法调用次数
 * 2. {@code recordFailuresOnly=true} 只在抛异常时计数
 * 3. Prometheus 输出：{@code _total} Counter + result/exception tag
 */
@RestController
@RequestMapping("/a08")
public class A08CountedController {

    /** 正常计数——每次调用 count+1 */
    @Counted("a08.counted.success")
    @GetMapping("/success")
    public String success() {
        return "Success — @Counted increments counter";
    }

    /** 只记录失败——正常调用不计数 */
    @Counted(value = "a08.counted.failures", recordFailuresOnly = true)
    @GetMapping("/maybe-fail")
    public String maybeFail() {
        return "No exception — recordFailuresOnly=true, skipped";
    }

    /** 尝试触发失败计数 */
    @Counted(value = "a08.counted.failures", recordFailuresOnly = true)
    @GetMapping("/fail")
    public String fail() {
        throw new RuntimeException("Intentional failure — @Counted will record this");
    }
}
