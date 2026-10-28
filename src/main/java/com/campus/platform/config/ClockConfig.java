package com.campus.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 统一时钟。业务代码通过注入 {@link Clock} 获取当前时间，便于测试中固定时间
 * （如选课时间窗口、请假“明天下午”的解析）。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(AppProperties appProperties) {
        return Clock.system(ZoneId.of(appProperties.timeZone()));
    }
}
