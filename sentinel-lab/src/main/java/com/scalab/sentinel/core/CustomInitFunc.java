package com.scalab.sentinel.core;

import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.init.InitFunc;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;

/**
 * B-05: 自定义 InitFunc——Sentinel 启动时通过 SPI 自动执行。
 *
 * <p>实现 {@link InitFunc} 接口，在 {@link #init()} 中执行初始化逻辑。
 * Sentinel 通过 {@code META-INF/services/com.alibaba.csp.sentinel.init.InitFunc}
 * 加载所有 InitFunc 实现，在 SpiLoader 初始化后自动调用。
 */
public class CustomInitFunc implements InitFunc {

    @Override
    public void init() throws Exception {
        RecordLog.info("[CustomInitFunc] Sentinel initialized — FlowRuleManager rules: "
                + FlowRuleManager.getRules().size());
    }
}
