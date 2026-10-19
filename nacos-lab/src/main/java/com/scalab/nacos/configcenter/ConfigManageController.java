package com.scalab.nacos.configcenter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置管理 — 灰度发布 + 历史回滚 + 加密（通过 Nacos OpenAPI 操作远端 Nacos）
 *
 * 场景一：配置灰度发布
 *   Nacos 2.x 支持 beta 配置（仅指定 IP 的客户端能拿到灰度值）。
 *   流程：发布 beta → 灰度 IP 验证 → 全量发布。
 *
 * 场景二：配置历史与回滚
 *   Nacos 每次变更保存历史快照，可通过 API 查询历史并回滚。
 *
 * 场景三：配置加密（简化演示）
 *   生产环境用 Jasypt 或 Nacos 加密插件保护敏感配置。
 *   这里用 BASE64 演示加密/解密概念，实际生产请用 Jasypt。
 */
@RestController
public class ConfigManageController {

    private static final Logger log = LoggerFactory.getLogger(ConfigManageController.class);
    private static final String NACOS_URL = "http://21.130.247.89:18848";
    private static final String TENANT = "sca-lab-dev";

    // ========== 场景一：灰度发布 ==========

    /**
     * 发布灰度配置（beta）
     * 只有 betaIps 参数里指定的 IP 客户端能拿到灰度值
     */
    @PostMapping("/config/gray/publish")
    public Map<String, Object> grayPublish(
            @RequestParam String content,
            @RequestParam(defaultValue = "21.214.97.212") String betaIps) throws Exception {

        String dataId = "nacos-lab.yaml";
        String group = "DEFAULT_GROUP";
        String paramStr = "dataId=" + URLEncoder.encode(dataId, StandardCharsets.UTF_8)
                + "&group=" + URLEncoder.encode(group, StandardCharsets.UTF_8)
                + "&tenant=" + URLEncoder.encode(TENANT, StandardCharsets.UTF_8)
                + "&content=" + URLEncoder.encode(content, StandardCharsets.UTF_8)
                + "&betaIps=" + URLEncoder.encode(betaIps, StandardCharsets.UTF_8);

        String result = postToNacos("/nacos/v1/cs/configs", paramStr);
        Map<String, Object> response = new HashMap<>();
        response.put("action", "gray_publish");
        response.put("betaIps", betaIps);
        response.put("content", content);
        response.put("result", result);
        response.put("note", "灰度已发布到指定 IP。调 /config/gray/stop 取消灰度，"
                + "/config/gray/promote 全量发布");
        log.info("[灰度发布] betaIps={}, result={}", betaIps, result);
        return response;
    }

    /**
     * 取消灰度（回到原配置）
     */
    @PostMapping("/config/gray/stop")
    public Map<String, Object> grayStop() throws Exception {
        String dataId = "nacos-lab.yaml";
        String group = "DEFAULT_GROUP";

        URL url = new URL(NACOS_URL + "/nacos/v1/cs/configs/"
                + "?dataId=" + URLEncoder.encode(dataId, StandardCharsets.UTF_8)
                + "&group=" + URLEncoder.encode(group, StandardCharsets.UTF_8)
                + "&tenant=" + URLEncoder.encode(TENANT, StandardCharsets.UTF_8));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("DELETE");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();

        Map<String, Object> result = new HashMap<>();
        result.put("action", "gray_stop");
        result.put("result", response);
        result.put("note", "灰度已取消。原配置恢复生���");
        return result;
    }

    // ========== 场景二：配置历史与回滚 ==========

    /**
     * 查询配置历史
     */
    @GetMapping("/config/history")
    public Map<String, Object> history() throws Exception {
        String dataId = "nacos-lab.yaml";
        String group = "DEFAULT_GROUP";

        URL url = new URL(NACOS_URL + "/nacos/v1/cs/history/configs"
                + "?dataId=" + URLEncoder.encode(dataId, StandardCharsets.UTF_8)
                + "&group=" + URLEncoder.encode(group, StandardCharsets.UTF_8)
                + "&tenant=" + URLEncoder.encode(TENANT, StandardCharsets.UTF_8)
                + "&pageNo=1&pageSize=50&search=accurate");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        String raw = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();

        Map<String, Object> result = new HashMap<>();
        result.put("dataId", dataId);
        result.put("raw", raw);
        result.put("note", "每次配置变更都在这里。找到变更前的 id，用 POST /config/rollback?id=xxx 回滚");
        return result;
    }

    /**
     * 查看历史项详情（含完整配置内容）
     */
    @GetMapping("/config/history/detail")
    public Map<String, Object> historyDetail(@RequestParam String nid) throws Exception {
        String dataId = "nacos-lab.yaml";
        String group = "DEFAULT_GROUP";

        URL url = new URL(NACOS_URL + "/nacos/v1/cs/history/config"
                + "?dataId=" + URLEncoder.encode(dataId, StandardCharsets.UTF_8)
                + "&group=" + URLEncoder.encode(group, StandardCharsets.UTF_8)
                + "&tenant=" + URLEncoder.encode(TENANT, StandardCharsets.UTF_8)
                + "&nid=" + nid);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        String content = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();

        Map<String, Object> result = new HashMap<>();
        result.put("nid", nid);
        result.put("content", content);
        result.put("note", "这就是该历史版本的完整配置内容，可复制到 /config/gray/publish 或 Nacos 控制台回滚");
        return result;
    }

    // ========== 场景三：配置加密（Jasypt 简化演示）==========

    /**
     * 加密/解密演示（BASE64，仅供 lab 学习用）
     */
    @GetMapping("/config/encrypt/demo")
    public Map<String, Object> encryptDemo(@RequestParam(defaultValue = "Xhs@2026#MySQL") String raw) {
        String encoded = java.util.Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        byte[] decoded = java.util.Base64.getDecoder().decode(encoded);
        String decodedStr = new String(decoded, StandardCharsets.UTF_8);

        Map<String, Object> result = new HashMap<>();
        result.put("raw", raw);
        result.put("encoded", encoded);
        result.put("decoded", decodedStr);
        result.put("production_note", "生产环境用 Jasypt：encrypted value 用 ENC(...) 包裹，"
                + "master key 通过环境变量或 K8s Secret 注入。BASE64 只是概念演示。");
        return result;
    }

    // ========== 工具方法 ==========

    private String postToNacos(String path, String paramStr) throws Exception {
        URL url = new URL(NACOS_URL + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(paramStr.getBytes(StandardCharsets.UTF_8));
        }

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
