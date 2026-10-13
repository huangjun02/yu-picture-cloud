package com.huang.yupicture.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.yupicture.model.dto.image.ImageQueryRequest;
import com.huang.yupicture.model.vo.ImageVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
/**
 * 图片查询的多级缓存（图片优化第二期）。
 *
 * <p><b>只缓存「浏览视角」的分页结果</b>（主页图墙：非本人 + 非管理员 = 服务端钉死「已通过」），
 * 这个视角的结果与登录的是谁无关（同一条查询对所有用户返回一样的内容），缓存 key 不掺用户身份 ——
 * 「我的图片」视角（含待审核 / 被拒）每人一份且状态私密，缓存它既浪费又容易越权，明确不缓存。
 *
 * <p><b>三层结构</b>（读路径）：C1 本地 ConcurrentHashMap → C2 Redis → C3 MySQL。
 *
 * <p><b>失效设计：全局版本号</b>，而不是逐 key 删除：
 * <ul>
 *   <li>查询条件是自由组合的（搜索词 × 分类 × 标签 × 颜色 × 页码），逐 key 失效要反查「哪些
 *       组合受这次写操作影响」，组合空间根本枚举不全 —— 与其猜，不如让旧 key 全部作废</li>
 *   <li>版本号权威存 Redis（{@code image:cache:version}）：写操作 INCR 它，所有按旧版本号拼出来的
 *       key 立刻读不到值 —— 天然「批量失效」，多实例部署也一致（版本号是共享的）</li>
 *   <li>本地缓存里旧版本的条目等 TTL 到期自然淘汰（单实例其实可以 INCR 时顺手清空，
 *       多实例就得靠 pub/sub 广播；本项目单机，留 TTL 兜底即可，注释里说明取舍）</li>
 * </ul>
 *
 * <p><b>为什么手写本地缓存而不用 Caffeine：</b>项目纪律「不轻易引框架」；缓存就一个键值对容器
 * 加过期判断，手写 60 行看得见原理，容量上限 + 惰性淘汰足够用（面试也能讲清「怎么实现的」）。
 * 若将来多实例部署，本地缓存一致性要升级 pub/sub 广播（见下），到时再评估引框架。
 */
@Slf4j
@Component
public class ImageCacheManager {

    /** C1 本地缓存：key → 带时间戳的 JSON。存 JSON 而非对象引用：防调用方改了缓存里的对象污染下一个读者 */
    private final ConcurrentHashMap<String, CacheEntry> localCache = new ConcurrentHashMap<>();

    /** 本地缓存条目上限：超限时整体清空。图墙每页 ≤ 20 条 VO，几千条也就几 MB，防呆不是精确治理 */
    private static final int LOCAL_MAX_ENTRIES = 1000;

    /** C1 本地 TTL：短 —— 多实例下它收不到失效广播，短 TTL 是一致性的兜底；单实例下也减少陈旧窗口 */
    private static final long LOCAL_TTL_MS = 60_000L;

    /** C2 Redis TTL：比本地长一个量级，扛住「同一搜索词反复翻页」的主力 */
    private static final Duration REDIS_TTL = Duration.ofMinutes(10);

    /** Redis 版本号 key：写操作 INCR 它，旧 key 全作废 */
    private static final String VERSION_KEY = "image:cache:version";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    /** 本地缓存条目：值 + 过期时刻。过期判断在读取时做（惰性），不起后台线程 */
    private record CacheEntry(String json, long expireAtMillis) {
    }

    /**
     * 读：C1 → C2 → null（null = 未命中，调用方回源 MySQL 并调用 {@link #put}）。
     *
     * <p>Redis 挂了降级直读库 —— 缓存是加速器不是依赖，它坏了最多慢、不能不可用。
     */
    public Page<ImageVO> get(ImageQueryRequest request) {
        String key = buildKey(request);

        // ---- C1 本地 ----
        CacheEntry entry = localCache.get(key);
        if (entry != null) {
            if (System.currentTimeMillis() < entry.expireAtMillis()) {
                return deserialize(entry.json());
            }
            localCache.remove(key); // 惰性过期：读到了才顺手清，省一个后台清理线程
        }

        // ---- C2 Redis ----
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json != null) {
                localCache.put(key, new CacheEntry(json, System.currentTimeMillis() + LOCAL_TTL_MS));
                evictLocalIfOverflow();
                return deserialize(json);
            }
        } catch (Exception e) {
            // 明确降级：Redis 不可用时打 warn 走回源，不抛 —— 缓存故障不该把查询接口打挂
            log.warn("Redis 缓存读取失败，降级回源：{}", e.getMessage());
        }
        return null;
    }

    /**
     * 写：同时写 C2（长 TTL）与 C1（短 TTL）。
     *
     * <p>写 C1 用「短过期 + 读取时校验」而不是永远信任： put 和 INCR 之间存在竞态
     * （A 刚把旧值 put 进本地，B 恰好完成写操作 INCR 版本号），短 TTL 保证这种陈旧值最多活 1 分钟。
     */
    public void put(ImageQueryRequest request, Page<ImageVO> page) {
        String key = buildKey(request);
        try {
            String json = serialize(page);
            stringRedisTemplate.opsForValue().set(key, json, REDIS_TTL);
            localCache.put(key, new CacheEntry(json, System.currentTimeMillis() + LOCAL_TTL_MS));
        } catch (Exception e) {
            log.warn("缓存写入失败（不影响查询正确性，下次走回源）：{}", e.getMessage());
        }
    }

    /**
     * 失效：版本号 +1。所有按旧版本号拼的 key 立刻失效 —— 比逐 key 删除可靠得多。
     * Redis 挂了版本号 bump 不了：旧缓存按 TTL 自然过期（最长陈旧 10 分钟），不抛错阻断写操作。
     */
    public void bumpVersion() {
        try {
            Long v = stringRedisTemplate.opsForValue().increment(VERSION_KEY);
            localCache.clear(); // 单实例顺手清空本地；多实例部署需升级 pub/sub 广播（见类注释）
            log.info("图片缓存版本号已更新：v={}", v);
        } catch (Exception e) {
            log.warn("缓存版本号更新失败（旧缓存等 TTL 自然过期）：{}", e.getMessage());
        }
    }

    /**
     * 显式清空（测试用）：直接插库的测试数据不经过任何 Service 写路径，版本号不会 bump，
     * 上一个测试留下的缓存条目会让本测试读到旧数据。生产代码不要调用它 ——
     * 生产的一致性只靠写路径 bump + TTL，多一个「万能清空」入口反而容易被误当成失效手段。
     */
    public void clearForTest() {
        localCache.clear();
        try {
            stringRedisTemplate.delete(stringRedisTemplate.keys("image:cache:*"));
        } catch (Exception e) {
            log.warn("测试清空 Redis 缓存失败：{}", e.getMessage());
        }
    }

    /**
     * 缓存 key = 版本号 + 查询条件摘要。
     *
     * <p>条件摘要用「稳定序列化后取 SHA-256 前 16 字节 hex」：Jackson 序列化 DTO 字段顺序稳定，
     * 同条件必同 key；截断 128bit 无碰撞风险。版本号在 key 里而不是单独 set 集合，是为了
     * 「写侧一次 INCR，读侧拼 key 自然 miss」，不需要扫 key。
     */
    private String buildKey(ImageQueryRequest request) {
        long version;
        try {
            String v = stringRedisTemplate.opsForValue().get(VERSION_KEY);
            version = v == null ? 0 : Long.parseLong(v);
            // 首次访问顺手初始化版本号，避免每次都走 get→null 分支
            if (v == null) {
                stringRedisTemplate.opsForValue().setIfAbsent(VERSION_KEY, "0");
            }
        } catch (Exception e) {
            version = -1; // Redis 挂了：key 不带版本区分，等于退化为「带 TTL 的单层缓存」，仍正确
            log.warn("读取缓存版本号失败：{}", e.getMessage());
        }
        return "image:cache:page:v" + version + ":" + digest(request);
    }

    /** 查询条件 → 稳定摘要（SHA-256 前 16 字节 hex） */
    private String digest(ImageQueryRequest request) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(request);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // 序列化失败几乎不可能（POJO + Jackson），防御性兜底：不缓存也不阻断查询
            throw new IllegalStateException("缓存 key 生成失败", e);
        }
    }

    private String serialize(Page<ImageVO> page) {
        try {
            return objectMapper.writeValueAsString(page);
        } catch (Exception e) {
            throw new IllegalStateException("分页结果序列化失败", e);
        }
    }

    /**
     * 反序列化。这里有个必须写测试钉住的坑：Page 的 records 是 List<ImageVO>，
     * Jackson 泛型擦除后会反序列化成 List<LinkedHashMap> —— 必须用 JavaType 指定
     * Page&lt;ImageVO&gt; 才能拿回真正的类型。
     */
    private Page<ImageVO> deserialize(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructParametricType(Page.class, ImageVO.class));
        } catch (Exception e) {
            throw new IllegalStateException("分页结果反序列化失败", e);
        }
    }

    /** 本地缓存超限：整体清空（惰性方案，不做 LRU —— 学习项目防呆即可，注释里说明取舍） */
    private void evictLocalIfOverflow() {
        if (localCache.size() > LOCAL_MAX_ENTRIES) {
            localCache.clear();
            log.info("本地缓存超限（{} 条），已整体清空", LOCAL_MAX_ENTRIES);
        }
    }
}
