package com.scalab.nacos.discovery;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端缓存与故障转移 — 验证 Nacos 挂了之后客户端凭什么还能发现服务
 *
 * 核心机制：
 *   1. Nacos client 会在本地磁盘保存服务列表缓存
 *   2. 路径: $HOME/nacos/naming/{namespace}/DEFAULT_GROUP/@@${serviceName}@@
 *   3. 启动时、服务列表变更时、定时（10s）都会刷新缓存文件
 *   4. Nacos Server 不可达时，SDK 自动从缓存文件恢复服务列表 → 业务不受影响
 *
 * 验证步骤：
 *   1. 调 /discovery/cache/info 查看缓存文件是否存在 + 内容
 *   2. Nacos 可达 → /discovery/services 返回正常服务列表
 *   3。Nacos 不可达 → /discovery/services 仍返回缓存数据（验证关键能力）
 */
@RestController
public class DiscoveryCacheController {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryCacheController.class);

    @Autowired(required = false)
    private NacosDiscoveryProperties discoveryProperties;

    @Autowired(required = false)
    private DiscoveryClient discoveryClient;

    /**
     * 查看 Nacos client 本地缓存文件信息
     *
     * 返回：缓存目录路径、文件列表、最后修改时间
     */
    @GetMapping("/discovery/cache/info")
    public Map<String, Object> cacheInfo() {
        Map<String, Object> result = new HashMap<>();

        // Nacos client 缓存路径：$HOME/nacos/naming/
        String homeDir = System.getProperty("user.home");
        Path cacheDir = Paths.get(homeDir, "nacos", "naming");

        if (discoveryProperties != null) {
            // 命名空间目录
            String namespace = discoveryProperties.getNamespace();
            // serverAddr 中的 IP 用于目录名（格式为 IP_PORT）
            String serverAddr = discoveryProperties.getServerAddr();

            result.put("cacheDir", cacheDir.toString());
            result.put("namespace", namespace);
            result.put("serverAddr", serverAddr);

            List<Map<String, Object>> files = new ArrayList<>();
            if (Files.exists(cacheDir)) {
                try {
                    Files.walk(cacheDir, 2)
                            .filter(Files::isRegularFile)
                            .forEach(f -> {
                                Map<String, Object> fileInfo = new HashMap<>();
                                fileInfo.put("path", f.toString());
                                fileInfo.put("name", f.getFileName().toString());
                                try {
                                    long size = Files.size(f);
                                    fileInfo.put("sizeBytes", size);
                                    fileInfo.put("lastModified", LocalDateTime.ofInstant(
                                            Files.getLastModifiedTime(f).toInstant(),
                                            ZoneId.systemDefault())
                                            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

                                    // 展示前 50 行内容（JSON 格式的服务列表）
                                    if (size > 0) {
                                        List<String> lines = Files.readAllLines(f);
                                        if (lines.size() > 50) {
                                            fileInfo.put("contentPreview",
                                                    lines.subList(0, 50) + "\n...(" + (lines.size() - 50) + " more lines)");
                                        } else {
                                            fileInfo.put("contentPreview", lines);
                                        }
                                    }
                                } catch (Exception e) {
                                    fileInfo.put("error", e.getMessage());
                                }
                                files.add(fileInfo);
                            });
                } catch (Exception e) {
                    result.put("walkError", e.getMessage());
                }
            }
            result.put("cacheFileCount", files.size());
            result.put("cacheFiles", files);
            result.put("cacheExists", !files.isEmpty());
        } else {
            result.put("error", "NacosDiscoveryProperties 未注入");
        }

        return result;
    }

    /**
     * 对比：Nacos 实时服务列表 vs 缓存
     * 正常：两者一致
     * Nacos 挂了：实时查询失败，但缓存里的数据还在
     */
    @GetMapping("/discovery/cache/compare")
    public Map<String, Object> compare() {
        Map<String, Object> result = new HashMap<>();

        // 实时查询
        try {
            List<String> liveServices = discoveryClient != null
                    ? discoveryClient.getServices() : new ArrayList<>();
            result.put("liveQuery", Map.of(
                    "success", true,
                    "services", liveServices,
                    "count", liveServices.size()
            ));
        } catch (Exception e) {
            result.put("liveQuery", Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }

        // 缓存目录信息
        String homeDir = System.getProperty("user.home");
        Path cacheDir = Paths.get(homeDir, "nacos", "naming");
        File dir = cacheDir.toFile();
        boolean cacheExists = dir.exists() && dir.isDirectory();

        result.put("cacheDirExists", cacheExists);
        if (cacheExists) {
            File[] contents = dir.listFiles();
            result.put("cacheFileCount", contents != null ? contents.length : 0);
        }

        result.put("note", cacheExists
                ? "缓存文件存在。如果 Nacos 挂了，SDK 会从这些文件恢复服务列表。"
                : "缓存文件不存在。Nacos 启动后至少需要一次成功的服务发现才会写缓存。");

        return result;
    }
}
