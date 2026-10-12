package com.huang.yupicture.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.yupicture.manager.ImageCrawlClient.CrawlCandidate;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抓取响应解析测试（纯离线，不打网络）。
 *
 * <p>样本是**真实响应裁剪出来的**（src/test/resources/fixtures/openverse_search_sample.json），
 * 不是照着文档编的：凭想象写选择器的代码跑不起来，而且改解析逻辑时不该反复去打源站。
 * fixture 里有意塞了三条"坏数据"（缺 url / 非 http 协议 / 与第一条重复）——
 * 一条脏数据让整批失败是不可接受的，解析器必须跳过它们并照常返回其余记录。
 */
class ImageCrawlClientTest {

    private static JsonNode fixture() throws Exception {
        try (InputStream in = ImageCrawlClientTest.class.getResourceAsStream(
                "/fixtures/openverse_search_sample.json")) {
            assertNotNull(in, "测试样本必须存在：src/test/resources/fixtures/openverse_search_sample.json");
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void parseShouldReturnValidCandidatesAndSkipBadOnes() throws Exception {
        List<CrawlCandidate> candidates = ImageCrawlClient.parseSearchResponse(fixture());

        // fixture 有 6 条：3 条正常 + 缺 url + ftp 协议 + 重复地址
        assertEquals(3, candidates.size(), "只应有 3 条可用候选（脏数据和重复项都要被跳过）");
        for (CrawlCandidate c : candidates) {
            assertTrue(c.url().startsWith("https://"), "地址必须是 http(s)：" + c.url());
        }
    }

    @Test
    void parseShouldKeepLicenseAndAttribution() throws Exception {
        List<CrawlCandidate> candidates = ImageCrawlClient.parseSearchResponse(fixture());
        CrawlCandidate first = candidates.get(0);

        // 许可要拼成「类型 版本」，CC 系列的核心义务是署名，这两项丢了抓来的图就成了无主素材
        assertFalse(first.license().isEmpty(), "许可不能为空");
        assertTrue(first.license().contains(" "), "许可应拼成「类型 版本」，实际：" + first.license());
        assertFalse(first.attribution().isEmpty(), "署名信息不能为空");
        assertFalse(first.creator().isEmpty(), "作者不能为空");
    }

    @Test
    void parseShouldLimitSourceTags() throws Exception {
        List<CrawlCandidate> candidates = ImageCrawlClient.parseSearchResponse(fixture());
        for (CrawlCandidate c : candidates) {
            assertTrue(c.tags().size() <= 3, "来源标签最多带 3 个，多了会把筛选栏弄乱");
        }
    }

    @Test
    void parseShouldSurviveMalformedPayload() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // 整包为空 / results 不是数组 / 一条记录都没有 —— 一律返回空列表，不抛异常
        assertTrue(ImageCrawlClient.parseSearchResponse(null).isEmpty());
        assertTrue(ImageCrawlClient.parseSearchResponse(mapper.readTree("{}")).isEmpty());
        assertTrue(ImageCrawlClient.parseSearchResponse(mapper.readTree("{\"results\":\"oops\"}")).isEmpty());
        assertTrue(ImageCrawlClient.parseSearchResponse(mapper.readTree("{\"results\":[]}")).isEmpty());
    }
}
