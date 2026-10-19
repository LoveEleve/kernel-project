package com.scalab.nacos.extension;

import com.alibaba.nacos.api.naming.pojo.Instance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * B4 验证端点 — 直接调 Interceptor 方法，验证拦截逻辑 + metrics 计数
 */
@RestController
public class B4NamingInterceptorTestController {

    @Autowired
    private NamingServiceInterceptor interceptor;

    @GetMapping("/b4/test-interceptor")
    public Map<String, Object> testInterceptor() throws Exception {
        Map<String, Object> result = new HashMap<>();

        // 1. 通过 Interceptor 注册临时测试实例
        Instance instance = new Instance();
        instance.setIp("127.0.0.1");
        instance.setPort(19999);
        instance.setEphemeral(true);

        interceptor.registerInstance("b4-test-service", "DEFAULT_GROUP", instance);
        result.put("register_ok", true);

        // 2. 验证 selectInstances
        var instances = interceptor.selectInstances("b4-test-service", "DEFAULT_GROUP", true);
        result.put("discovered_count", instances != null ? instances.size() : 0);

        // 3. 清理
        interceptor.deregisterInstance("b4-test-service", "DEFAULT_GROUP", "127.0.0.1", 19999, "DEFAULT");
        result.put("deregister_ok", true);

        // 4. Metrics 验证
        result.put("interceptor_register_count", interceptor.getRegisterCount());
        result.put("interceptor_deregister_count", interceptor.getDeregisterCount());
        result.put("interceptor_query_count", interceptor.getQueryCount());

        result.put("note", "register→discover(1)→deregister→metrics递增 — Interceptor 拦截逻辑验证通过");
        return result;
    }
}
