package com.campus.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 高校 AI 综合服务平台后端启动类。
 * <p>
 * 采用模块化单体架构，各业务模块位于 {@code com.campus.platform.<module>} 包下。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CampusAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusAiApplication.class, args);
    }
}
