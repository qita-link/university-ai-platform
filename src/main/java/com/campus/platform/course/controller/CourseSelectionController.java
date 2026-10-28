package com.campus.platform.course.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.course.dto.SelectionCreateRequest;
import com.campus.platform.course.dto.SelectionView;
import com.campus.platform.course.service.CourseSelectionService;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "选课", description = "选课、退课与本人已选课程")
@RestController
@RequestMapping("/api/v1/course-selections")
public class CourseSelectionController {

    private final CourseSelectionService selectionService;

    public CourseSelectionController(CourseSelectionService selectionService) {
        this.selectionService = selectionService;
    }

    @Operation(summary = "选课", description = "需要 selection:select；校验选课窗口、容量、重复与时间冲突")
    @PreAuthorize("hasAuthority('selection:select')")
    @PostMapping
    public ApiResponse<SelectionView> select(@AuthenticationPrincipal AuthenticatedUser user,
                                             @Valid @RequestBody SelectionCreateRequest request) {
        return ApiResponse.ok(selectionService.select(user, request.courseId()));
    }

    @Operation(summary = "本人已选课程", description = "semesterId 为空时返回全部学期")
    @PreAuthorize("hasAuthority('course:read')")
    @GetMapping("/me")
    public ApiResponse<List<SelectionView>> mySelections(@AuthenticationPrincipal AuthenticatedUser user,
                                                         @RequestParam(required = false) Long semesterId) {
        return ApiResponse.ok(selectionService.mySelections(user, semesterId));
    }

    @Operation(summary = "退课", description = "需要 selection:select；仅本人且仍在选课窗口内可退")
    @PreAuthorize("hasAuthority('selection:select')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> drop(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        selectionService.drop(user, id);
        return ApiResponse.ok();
    }
}
