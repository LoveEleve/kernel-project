package com.scalab.tracing.b;

import brave.sampler.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * B02 自定义 Sampler——日志每次采样决策（对照 D7 §一.1）
 * 
 * 覆盖 BraveAutoConfiguration 的默认 Sampler（@ConditionalOnMissingBean）。
 * 行为与默认 ProbabilityBasedSampler 相同，但记录每次决策日志。
 */
@Component
public class B02DebugSampler extends Sampler {
    private static final Logger log = LoggerFactory.getLogger(B02DebugSampler.class);
    private long sampled;

    @Override
    public boolean isSampled(long traceId) {
        sampled++;
        log.info("[B02] Sampling decision: traceId={}, sampled=true (count={})",
                Long.toHexString(traceId), sampled);
        return true;
    }
}
