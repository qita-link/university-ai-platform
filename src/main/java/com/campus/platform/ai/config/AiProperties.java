package com.campus.platform.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 问答参数（前缀 app.ai）。模型本身仍由 spring.ai.model.chat 控制。
 *
 * @param topK             每次检索进入上下文的候选块数
 * @param maxContextTokens 资料区 Token 预算
 * @param maxAnswerTokens  回答最大 Token
 * @param temperature      采样温度
 * @param historyMessages  带入提示词的历史消息条数（不含当前问题）
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        Integer topK,
        Integer maxContextTokens,
        Integer maxAnswerTokens,
        Double temperature,
        Integer historyMessages) {

    public AiProperties {
        topK = topK == null || topK < 1 ? 5 : topK;
        maxContextTokens = maxContextTokens == null || maxContextTokens < 256 ? 3000 : maxContextTokens;
        maxAnswerTokens = maxAnswerTokens == null || maxAnswerTokens < 64 ? 800 : maxAnswerTokens;
        temperature = temperature == null || temperature < 0 ? 0.2 : temperature;
        historyMessages = historyMessages == null || historyMessages < 0 ? 6 : historyMessages;
    }
}
