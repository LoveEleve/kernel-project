package com.myxhs.demo.polygateway.lab.provider;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部端点：用于验证网关的按服务作用域排除（lab-provider 排除 / lab-audit 保留）。
 */
@RestController
@RequestMapping("/internal")
public class LabInternalController {

    @PostMapping("/reconcile")
    public String reconcile() {
        return "POST /api/internal/reconcile";
    }
}
