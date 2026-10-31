package com.campus.platform.forum.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.forum.entity.ForumSensitiveWord;
import com.campus.platform.forum.mapper.ForumSensitiveWordMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 论坛敏感词校验。
 * <p>
 * 命中即拒绝落库（而不是先发后审），因为校园论坛的违规内容一旦被截图传播就很难挽回。
 * 词库整体缓存在进程内，默认 60 秒刷新一次——改词库后最多延迟一分钟生效，不必重启。
 * <p>
 * 匹配方式是子串包含：中文不需要分词，直接包含判断对短词最可靠；
 * 代价是可能出现「误伤」（例如把含敏感词的正常讨论挡掉），所以错误提示会带上命中的词，方便用户改写。
 */
@Slf4j
@Service
public class SensitiveWordService {

    private final ForumSensitiveWordMapper wordMapper;
    private final Cache<String, Set<String>> cache;

    public SensitiveWordService(ForumSensitiveWordMapper wordMapper,
                                @Value("${app.forum.sensitive-word-ttl:60s}") Duration ttl) {
        this.wordMapper = wordMapper;
        this.cache = Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfterWrite(ttl)
                .build();
    }

    /** 当前生效的词表（按 TTL 缓存）。 */
    public Set<String> words() {
        return cache.get("words", key -> wordMapper.selectList(
                        new LambdaQueryWrapper<ForumSensitiveWord>().eq(ForumSensitiveWord::getEnabled, true))
                .stream()
                .map(ForumSensitiveWord::getWord)
                .filter(w -> w != null && !w.isBlank())
                .collect(Collectors.toUnmodifiableSet()));
    }

    /**
     * 校验若干段文本；任意一段命中即抛出 400。
     *
     * @param texts 标题、正文、评论内容等
     */
    public void check(String... texts) {
        Set<String> words = words();
        if (words.isEmpty()) {
            return;
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            String lower = text.toLowerCase();
            for (String word : words) {
                if (lower.contains(word.toLowerCase())) {
                    log.info("内容命中敏感词: word={}", word);
                    throw BusinessException.badRequest("内容包含不允许发布的词：" + word + "，请修改后重试");
                }
            }
        }
    }

    /** 词库变更后立即失效（管理端维护词表时调用）。 */
    public void invalidate() {
        cache.invalidateAll();
    }

    /** 管理端：列出全部词（含停用）。 */
    public List<ForumSensitiveWord> listAll() {
        return wordMapper.selectList(new LambdaQueryWrapper<ForumSensitiveWord>()
                .orderByAsc(ForumSensitiveWord::getId));
    }
}
