package com.huang.yupicture.service.impl;

import com.huang.yupicture.config.CrawlProperties;
import com.huang.yupicture.constant.UserConstant;
import com.huang.yupicture.common.BusinessException;
import com.huang.yupicture.common.ErrorCode;
import com.huang.yupicture.manager.ImageCrawlClient;
import com.huang.yupicture.manager.ImageCrawlClient.CrawlCandidate;
import com.huang.yupicture.model.dto.image.ImageCrawlRequest;
import com.huang.yupicture.model.dto.image.ImageUpdateRequest;
import com.huang.yupicture.model.entity.Image;
import com.huang.yupicture.model.entity.User;
import com.huang.yupicture.model.vo.ImageCrawlResultVO;
import com.huang.yupicture.model.vo.ImageVO;
import com.huang.yupicture.service.ImageCrawlService;
import com.huang.yupicture.service.ImageService;
import com.huang.yupicture.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 批量抓图实现。
 *
 * <p><b>整条链路只有"编排"，没有一行自己实现的下载/校验</b>：候选地址一律交给
 * {@link ImageService#uploadImageByUrl}，那里已经有 SSRF 校验（发请求之前拦内网/回环/云元数据）、
 * 5MB 上限、魔数校验、格式白名单、COS 上传、管理员自动过审。抓取是新入口，但**不是新通道**。
 *
 * <p><b>为什么逐条 try/catch 而不是整批事务</b>：抓取失败是常态（外链失效、格式不支持），
 * 一张失败不能连坐另外 19 张。反正每张图是独立的一次上传，本来也没有"整体成功/整体失败"的语义。
 */
@Slf4j
@Service
public class ImageCrawlServiceImpl implements ImageCrawlService {

    /** 关键词长度上限（超过就不是关键词了，是别的东西） */
    private static final int MAX_QUERY_LENGTH = 32;

    /** 名称里标题部分最长保留多少字符：图库列表要展示，太长的名字既没意义又会撑乱版面 */
    private static final int MAX_TITLE_IN_NAME = 24;

    /** 简介（署名信息）长度上限，与库列宽度对齐 */
    private static final int MAX_INTRODUCTION_LENGTH = 300;

    @Resource
    private ImageCrawlClient imageCrawlClient;

    @Resource
    private ImageService imageService;

    @Resource
    private UserService userService;

    @Resource
    private CrawlProperties crawlProperties;

    @Override
    public ImageCrawlResultVO crawl(ImageCrawlRequest request) {
        // 登录 + 管理员：抓取会往库里灌数据，普通用户不能碰
        User loginUser = userService.getLoginUser();
        if (!UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "仅管理员可批量抓取");
        }

        String query = validateQuery(request);
        int count = resolveCount(request.getCount());
        String category = resolveCategory(request.getCategory(), query);

        ImageCrawlResultVO result = new ImageCrawlResultVO();
        result.setQuery(query);

        // 要多少张就向源站要多少条（不再多要）：数量字段的语义是"最多处理几张"，
        // 按需索取对源站也更客气
        List<CrawlCandidate> candidates = imageCrawlClient.search(query, count);
        result.setCandidateCount(candidates.size());

        Set<String> seenUrls = new HashSet<>();
        int attempts = 0;
        for (CrawlCandidate candidate : candidates) {
            // 上限按"尝试处理的数量"算，不按"成功数"算：
            // 按成功数算的话，源站大面积失败时会一路往下试，用户传 10 却打了一整个候选列表；
            // 而且失败是常态（外链失效），行为必须可预期 —— 抓几张就是几张，成功失败如实回传
            if (attempts >= count) {
                break;
            }
            attempts++;
            ImageCrawlResultVO.Item item = new ImageCrawlResultVO.Item();
            item.setSourceUrl(candidate.url());

            // 本次结果内地址去重：源站偶尔返回同一个地址的多条记录
            if (!seenUrls.add(candidate.url())) {
                item.setStatus("SKIP");
                item.setReason("本次结果里地址重复");
                result.getItems().add(item);
                result.setSkipCount(result.getSkipCount() + 1);
                continue;
            }

            String name = buildImageName(candidate, query);
            item.setName(name);

            // 幂等：名字里带了源地址的短哈希，同一个关键词再抓一次 → 名字相同 → 跳过，
            // 不会把图库灌成两倍。⚠️ 已知局限：换关键词抓同一张图仍会重复（要根治得加 sourceUrl
            // 列并做唯一索引，那需要改表结构，见 docs/requirements.md 的待办）
            if (imageService.lambdaQuery().eq(Image::getName, name).count() > 0) {
                item.setStatus("SKIP");
                item.setReason("同名图片已存在（重复抓取已跳过）");
                result.getItems().add(item);
                result.setSkipCount(result.getSkipCount() + 1);
                continue;
            }

            try {
                ImageVO saved = imageService.uploadImageByUrl(candidate.url(), name);

                // 打上分类 / 标签 / 署名。走 updateImage 而不是直接 updateById：
                // 权限校验、标签规范化（JSON 数组）、审核语义（管理员编辑=自动过审）全在那儿，
                // 绕过去就等于把这些规则复制一份到抓取里，将来一定不同步
                ImageUpdateRequest update = new ImageUpdateRequest();
                update.setId(saved.getId());
                update.setCategory(category);
                update.setTags(resolveTags(request.getTags()));
                update.setIntroduction(buildIntroduction(candidate));
                imageService.updateImage(update);

                item.setStatus("SUCCESS");
                // 成功数不单独计数：它就是 successIds 的长度（一个数两个来源必然会对不上）
                result.getSuccessIds().add(saved.getId());
                log.info("抓图入库成功：name={}, category={}, url={}", name, category, candidate.url());
            } catch (Exception e) {
                // 失败不连坐：记下原因继续下一张（原因要回传给前端，不能只打个日志）
                item.setStatus("FAIL");
                item.setReason(rootMessage(e));
                log.warn("抓图入库失败：url={}, 原因={}", candidate.url(), rootMessage(e));
            }
            result.getItems().add(item);

            sleepInterval();
        }

        result.setSuccessCount(result.getSuccessIds().size());
        result.setFailCount((int) result.getItems().stream().filter(i -> "FAIL".equals(i.getStatus())).count());
        log.info("抓图结束：query={}, 候选={}, 成功={}, 跳过={}, 失败={}",
                query, result.getCandidateCount(), result.getSuccessCount(),
                result.getSkipCount(), result.getFailCount());
        return result;
    }

    /** 关键词：必填、去空白、限长 */
    private String validateQuery(ImageCrawlRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "检索关键词不能为空");
        }
        String query = request.getQuery().trim();
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "关键词不能超过 " + MAX_QUERY_LENGTH + " 个字符");
        }
        return query;
    }

    /**
     * 数量收敛到 [1, maxCount]。
     *
     * <p>不传给默认值、越界夹到边界而不是报错：用户输入 999 的本意是"尽可能多"，
     * 这时候报错只是在跟用户较劲，夹到上限并如实返回实际数量更合理。
     */
    private int resolveCount(Integer count) {
        int max = Math.max(crawlProperties.getMaxCount(), 1);
        if (count == null) {
            return Math.min(10, max);
        }
        return Math.min(Math.max(count, 1), max);
    }

    private String resolveCategory(String category, String query) {
        if (category != null && !category.trim().isEmpty()) {
            return category.trim();
        }
        // 不填分类就用关键词当分类：抓"风景"的图归到"风景"下，符合直觉
        return query;
    }

    private String resolveTags(String tags) {
        // 交给 updateImage 里的 normalizeTags 统一规范化（逗号分隔 → JSON 数组），
        // 这里不重复实现一遍规则
        return tags;
    }

    /**
     * 生成入库名称：{标题或关键词}_{源地址短哈希}。
     *
     * <p>哈希进名字是为了**幂等**：同一个关键词再抓一次，同一个地址算出的名字一样，
     * 先查后插就能跳过。名里带标题是为了可读 —— 图库里显示一串 uuid 谁也认不出是什么。
     */
    private String buildImageName(CrawlCandidate candidate, String query) {
        String base = sanitizeName(candidate.title());
        if (base.isEmpty()) {
            base = sanitizeName(query);
        }
        if (base.isEmpty()) {
            base = "抓取图片";
        }
        if (base.length() > MAX_TITLE_IN_NAME) {
            base = base.substring(0, MAX_TITLE_IN_NAME);
        }
        return base + "_" + shortHash(candidate.url());
    }

    /** 名称只留中英文、数字、下划线、连字符：其它字符（斜杠、引号、emoji）会在展示和导出时出乱子 */
    private String sanitizeName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[^\\p{IsHan}a-zA-Z0-9_-]", "").trim();
    }

    /** 源地址的短哈希（取 SHA-256 前 6 位十六进制，足够避免同名冲突） */
    private String shortHash(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            // 极不可能发生；真发生了也不能让整批抓取挂掉，退化成时间戳保证名字不重复
            return String.valueOf(System.nanoTime() % 1000000);
        }
    }

    /**
     * 简介 = 署名信息。
     *
     * <p>CC 系列许可的**核心义务就是署名**：抓来的图不带作者与许可，
     * 就等于把"可免费使用"误传成了"无主素材"。所以这条不是可选装饰，
     * 而是这类数据的必要字段（源站没给署名信息时才退化成一句来源说明）。
     */
    private String buildIntroduction(CrawlCandidate candidate) {
        if (!crawlProperties.isWriteAttribution()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (!candidate.attribution().isEmpty()) {
            sb.append(candidate.attribution());
        } else {
            sb.append("来源：Openverse 检索结果");
            if (!candidate.creator().isEmpty()) {
                sb.append("，作者 ").append(candidate.creator());
            }
            if (!candidate.license().isEmpty()) {
                sb.append("，许可 ").append(candidate.license());
            }
        }
        String text = sb.toString().trim();
        return text.length() > MAX_INTRODUCTION_LENGTH ? text.substring(0, MAX_INTRODUCTION_LENGTH) : text;
    }

    /** 取最里层异常的 message：包装异常直接 toString 会甩一大串类名给用户看 */
    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg;
    }

    /**
     * 每张之间歇一下。
     *
     * <p>抓的是别人的图床，发太快是给源站添麻烦（也是被封的直接原因）。
     * 间隔做成配置项：本机能接受、源站也不至于觉得被刷。
     */
    private void sleepInterval() {
        long interval = Math.max(crawlProperties.getIntervalMs(), 0);
        if (interval == 0) {
            return;
        }
        try {
            Thread.sleep(interval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
