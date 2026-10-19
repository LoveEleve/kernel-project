package com.scalab.sentinel.core;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

/**
 * B-03: 自定义 DataSource——从本地 JSON 文件读取流控规则。
 *
 * <p>/tmp/flow-rules.json 格式：
 * <pre>  [{"resource":"sentinelApi","grade":1,"count":99.0}]</pre>
 */
public class FileFlowRuleDataSource {

    private static final Logger log = LoggerFactory.getLogger(FileFlowRuleDataSource.class);

    public static void init(String filePath) {
        try {
            String json = new String(Files.readAllBytes(Paths.get(filePath)));
            List<FlowRule> rules = JSON.parseObject(json, new TypeReference<List<FlowRule>>() {});
            if (rules != null && !rules.isEmpty()) {
                FlowRuleManager.loadRules(rules);
                log.info("[FileFlowRuleDataSource] Loaded {} rules from {}", rules.size(), filePath);
            }
        } catch (Exception e) {
            log.warn("[FileFlowRuleDataSource] Failed to load: {}", e.getMessage());
        }
    }
}
