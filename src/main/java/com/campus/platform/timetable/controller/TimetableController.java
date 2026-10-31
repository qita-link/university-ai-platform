package com.campus.platform.timetable.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.timetable.dto.TimetableItem;
import com.campus.platform.timetable.service.TimetableService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统一课表：把课程模块的手工排课与自动排课的已发布结果合并成一个视角。
 */
@Tag(name = "统一课表", description = "本人课表（自动排课优先，手工排课兜底）")
@RestController
@RequestMapping("/api/v1/timetables")
public class TimetableController {

    private final TimetableService timetableService;

    public TimetableController(TimetableService timetableService) {
        this.timetableService = timetableService;
    }

    @Operation(summary = "我的课表", description = """
            学生返回已选课程、教师返回任教课程的排课。
            每门课优先取自动排课已发布结果（带真实教室），没有排课结果时回退到课程模块的手工排课；
            `source` 字段标明来源（SCHEDULING / COURSE）。不传 semesterId 表示不限学期。""")
    @GetMapping("/me")
    public ApiResponse<List<TimetableItem>> mine(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @RequestParam(required = false) Long semesterId) {
        return ApiResponse.ok(timetableService.mine(user, semesterId));
    }
}
