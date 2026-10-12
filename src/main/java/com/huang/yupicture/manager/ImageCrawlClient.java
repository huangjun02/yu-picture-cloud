package com.huang.yupicture.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.yupicture.config.CrawlProperties;
import com.huang.yupicture.common.BusinessException;
import com.huang.yupicture.common.ErrorCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 图片检索客户端：按关键词向源站要一批候选图片地址。
 *
 * <p><b>只负责"检索"，不负责"入库"</b>：拿回候选地址后交给
 * {@code ImageService.uploadImageByUrl} 走与 URL 传图完全相同的下载/校验/入库链路 ——
 * 抓取绝不能自己另开一条下载通道，否则 SSRF 防线、魔数校验、大小限制都会在抓取路径上失效
 * （那些 URL 来自第三方页面，属于"外部可影响"的输入，正是最需要防的）。
 *
 * <p>解析部分抽成静态方法（{@link #parseSearchResponse}）：可以拿真实响应样本离线写测试，
 * 改解析逻辑不用真的去打源站。
 */
@Slf4j
@Component
public class ImageCrawlClient {

    /**
     * 一条候选图片。
     *
     * <p>用 record：它是一次性的传输载体，没有可变状态，写成一个只有 getter 的类纯属样板代码。
     */
    public record CrawlCandidate(String url, String title, String creator, String license,
                                 String attribution, List<String> tags) {
    }

    /** 来源标签最多带几个：标签是给人筛的，带一堆英文标签只会把筛选栏弄乱 */
    private static final int MAX_SOURCE_TAGS = 3;

    @Resource
    private CrawlProperties crawlProperties;

    @Resource
    private OutboundHttpClient outboundHttpClient;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 按关键词检索。
     *
     * @param query 关键词
     * @param limit 想要几条
     * @return 候选列表（已按地址去重）；源站不可用时抛 BusinessException，由调用方决定怎么呈现
     */
    public List<CrawlCandidate> search(String query, int limit) {
        String url = buildSearchUrl(query, Math.max(limit, 1));
        log.info("抓图检索：query={}, url={}", query, url);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    // 声明身份的 UA：合规抓取不伪装浏览器
                    .header("User-Agent", crawlProperties.getUserAgent())
                    .header("Accept", "application/json")
                    .timeout(Duration.ofMillis(crawlProperties.getTimeoutMs()))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = outboundHttpClient.get()
                    .send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("源站返回 " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            return parseSearchResponse(root);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("抓图检索失败：query={}", query, e);
            // 用项目统一的 BusinessException：全局异常处理器认它，
            // 直接抛 RuntimeException 会变成 50000「系统内部异常」，用户看不到真正原因
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "检索失败：" + e.getMessage());
        }
    }

    /** 拼检索 URL（关键词要 URL 编码 —— 中文不编码发出去会被源站当乱码） */
    private String buildSearchUrl(String query, int limit) {
        String base = crawlProperties.getSearchUrl();
        String sep = base.contains("?") ? "&" : "?";
        return base + sep
                + "q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&page_size=" + limit;
    }

    /**
     * 解析检索响应。抽成静态方法是为了能脱离网络测（拿真实响应样本喂进来）。
     *
     * <p>容错原则：**单个字段缺失不该让整批失败**。缺 url 的那条直接跳过，
     * 其余照常返回 —— 一条脏数据把 20 张图全废掉是不划算的。
     */
    public static List<CrawlCandidate> parseSearchResponse(JsonNode root) {
        List<CrawlCandidate> candidates = new ArrayList<>();
        if (root == null) {
            return candidates;
        }
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            return candidates;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : results) {
            String url = node.path("url").asText("").trim();
            if (url.isEmpty() || !isHttpUrl(url) || !seen.add(url)) {
                continue;
            }
            List<String> tags = new ArrayList<>();
            JsonNode tagNodes = node.path("tags");
            if (tagNodes.isArray()) {
                for (JsonNode tagNode : tagNodes) {
                    if (tags.size() >= MAX_SOURCE_TAGS) {
                        break;
                    }
                    String name = tagNode.path("name").asText("").trim();
                    if (!name.isEmpty() && !tags.contains(name)) {
                        tags.add(name);
                    }
                }
            }
            String license = node.path("license").asText("").trim();
            String version = node.path("license_version").asText("").trim();
            String licenseText = license.isEmpty() ? "" : (version.isEmpty() ? license : license + " " + version);
            candidates.add(new CrawlCandidate(
                    url,
                    node.path("title").asText("").trim(),
                    node.path("creator").asText("").trim(),
                    licenseText,
                    node.path("attribution").asText("").trim(),
                    tags));
        }
        return candidates;
    }

    private static boolean isHttpUrl(String url) {
        String lower = url.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
