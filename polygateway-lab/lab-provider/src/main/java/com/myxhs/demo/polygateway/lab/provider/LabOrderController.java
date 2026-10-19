package com.myxhs.demo.polygateway.lab.provider;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单端点：演示六维匹配（Path / Method / Consumes）与"更具体者优先"。
 */
@RestController
@RequestMapping("/orders")
public class LabOrderController {

    @GetMapping("/{id}")
    public String get(@PathVariable String id) {
        return "GET /api/orders/" + id;
    }

    @GetMapping("/special")
    public String special() {
        return "GET /api/orders/special";
    }

    @GetMapping("/{id}/lines")
    public String lines(@PathVariable String id) {
        return "GET /api/orders/" + id + "/lines";
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public String create(@RequestBody(required = false) String body) {
        return "POST /api/orders";
    }

    @PostMapping(value = "/bulk", consumes = { MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE })
    public String bulk(@RequestBody(required = false) String body) {
        return "POST /api/orders/bulk";
    }
}
