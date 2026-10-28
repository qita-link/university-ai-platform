package com.campus.platform.system;

import com.campus.platform.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * 公开的系统信息，供前端获取服务器时间与业务时区（用于“明天下午”等相对时间展示）。
 * 不返回任何配置细节、依赖版本或环境信息。
 */
@Tag(name = "系统")
@RestController
@RequestMapping("/api/v1/system")
public class SystemInfoController {

    private final Clock clock;
    private final String applicationName;

    public SystemInfoController(Clock clock, @Value("${spring.application.name}") String applicationName) {
        this.clock = clock;
        this.applicationName = applicationName;
    }

    @Operation(summary = "系统信息", description = "无需登录")
    @GetMapping("/info")
    public ApiResponse<SystemInfo> info() {
        return ApiResponse.ok(new SystemInfo(applicationName, "v1", OffsetDateTime.now(clock),
                clock.getZone().getId()));
    }

    public record SystemInfo(String name, String apiVersion, OffsetDateTime serverTime, String timeZone) {
    }
}
