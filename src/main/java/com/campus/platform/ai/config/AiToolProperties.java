package com.campus.platform.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AI 工具调用参数（前缀 app.ai.tool）。
 *
 * @param confirmTtl 写操作待确认记录的有效期；超时后确认返回 409
 */
@ConfigurationProperties(prefix = "app.ai.tool")
public record AiToolProperties(Duration confirmTtl) {

    public AiToolProperties {
        confirmTtl = confirmTtl == null || confirmTtl.isNegative() || confirmTtl.isZero()
                ? Duration.ofMinutes(10) : confirmTtl;
    }
}
