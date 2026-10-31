package com.campus.platform.setting.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.setting.dto.SettingUpdateRequest;
import com.campus.platform.setting.dto.SettingView;
import com.campus.platform.setting.service.SettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "设置", description = "平台运行期设置（请假审批流程等）")
@RestController
@RequestMapping("/api/v1/settings")
public class SettingController {

    private final SettingService settingService;

    public SettingController(SettingService settingService) {
        this.settingService = settingService;
    }

    @Operation(summary = "设置项列表", description = "需要 system:config")
    @PreAuthorize("hasAuthority('system:config')")
    @GetMapping
    public ApiResponse<List<SettingView>> list() {
        return ApiResponse.ok(settingService.list());
    }

    @Operation(summary = "修改设置项", description = "需要 system:config；请假审批不能同时关闭教师与导员")
    @PreAuthorize("hasAuthority('system:config')")
    @PutMapping("/{key}")
    public ApiResponse<SettingView> update(@AuthenticationPrincipal AuthenticatedUser user,
                                           @PathVariable String key,
                                           @Valid @RequestBody SettingUpdateRequest request) {
        return ApiResponse.ok(settingService.update(user, key, request.value()));
    }

    @Operation(summary = "当前请假审批流程", description = "所有登录用户可读，用于在页面上说明「谁会审我」")
    @GetMapping("/leave-flow")
    public ApiResponse<Map<String, Object>> leaveFlow() {
        SettingService.LeaveFlow flow = settingService.leaveFlow();
        return ApiResponse.ok(Map.of(
                "teacherRequired", flow.teacherRequired(),
                "counselorRequired", flow.counselorRequired(),
                "stages", flow.stages(),
                "totalStages", flow.totalStages()));
    }
}
