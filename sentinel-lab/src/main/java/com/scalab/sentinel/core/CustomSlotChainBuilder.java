package com.scalab.sentinel.core;

import com.alibaba.csp.sentinel.slotchain.ProcessorSlotChain;
import com.alibaba.csp.sentinel.slotchain.SlotChainBuilder;

/**
 * B-02: 自定义 SlotChainBuilder——在 Sentinel 9 个默认 Slot 末尾追加 TimingSlot。
 *
 * <p>实现 {@link SlotChainBuilder}，先用默认 SPI 加载 9 个官方 Slot，
 * 再追加 B-01 的 {@link TimingSlot} 作为观测层。
 */
public class CustomSlotChainBuilder implements SlotChainBuilder {

    @Override
    public ProcessorSlotChain build() {
        // 复用默认的链构建（9 个 Slot + SPI 排序）
        ProcessorSlotChain chain = new com.alibaba.csp.sentinel.slots.DefaultSlotChainBuilder().build();
        // 追加自定义 Slot
        chain.addLast(new TimingSlot());
        return chain;
    }
}
