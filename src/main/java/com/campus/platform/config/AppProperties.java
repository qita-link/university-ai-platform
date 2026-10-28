package com.campus.platform.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 平台通用配置（前缀 app）。各模块的专属配置放在各自模块的 Properties 类中。
 *
 * @param timeZone 业务时区，用于“今天/明天”等日期计算与时间字段填充
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(@NotBlank String timeZone) {
}
