package com.campus.platform.course.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.course.dto.ClassCounselorRequest;
import com.campus.platform.course.dto.ClassCounselorView;
import com.campus.platform.course.dto.LeaveDecisionRequest;
import com.campus.platform.course.dto.LeaveQuery;
import com.campus.platform.course.dto.LeaveRevokeRequest;
import com.campus.platform.course.dto.LeaveSubmitRequest;
import com.campus.platform.course.dto.LeaveView;
import com.campus.platform.course.service.LeaveRequestService;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "请假", description = "学生请假申请、教师审批与撤销")
@RestController
@RequestMapping("/api/v1/leave-requests")
public class LeaveRequestController {

    private final LeaveRequestService leaveService;

    public LeaveRequestController(LeaveRequestService leaveService) {
        this.leaveService = leaveService;
    }

    @Operation(summary = "提交请假", description = "需要 leave:submit；只能对本人已选课程请假")
    @PreAuthorize("hasAuthority('leave:submit')")
    @PostMapping
    public ApiResponse<LeaveView> submit(@AuthenticationPrincipal AuthenticatedUser user,
                                         @Valid @RequestBody LeaveSubmitRequest request) {
        return ApiResponse.ok(leaveService.submit(user, request));
    }

    @Operation(summary = "本人请假记录")
    @PreAuthorize("hasAuthority('leave:submit')")
    @GetMapping("/me")
    public ApiResponse<PageResult<LeaveView>> myRequests(@AuthenticationPrincipal AuthenticatedUser user,
                                                         @Valid @ParameterObject LeaveQuery query) {
        return ApiResponse.ok(leaveService.myRequests(user, query));
    }

    @Operation(summary = "待我审批", description = "教师只看自己任教课程；管理员（leave:manage_all）看全部；默认只返回 PENDING")
    @PreAuthorize("hasAuthority('leave:review') or hasAuthority('leave:manage_all')")
    @GetMapping("/to-review")
    public ApiResponse<PageResult<LeaveView>> toReview(@AuthenticationPrincipal AuthenticatedUser user,
                                                       @Valid @ParameterObject LeaveQuery query) {
        return ApiResponse.ok(leaveService.toReview(user, query));
    }

    @Operation(summary = "请假详情", description = "申请人、任课教师、导员、学生会与管理员可读；其他用户 404")
    @GetMapping("/{id}")
    public ApiResponse<LeaveView> get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return ApiResponse.ok(leaveService.get(user, id));
    }

    @Operation(summary = "全部请假查询",
            description = "需要 leave:read_all：学生会与管理员看全部，教师看自己任教课程与所带班级")
    @PreAuthorize("hasAuthority('leave:read_all')")
    @GetMapping("/all")
    public ApiResponse<PageResult<LeaveView>> all(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @Valid @ParameterObject LeaveQuery query) {
        return ApiResponse.ok(leaveService.all(user, query));
    }

    @Operation(summary = "通过请假", description = "任课教师或管理员；并发审批只有一个成功")
    @PreAuthorize("hasAuthority('leave:review') or hasAuthority('leave:manage_all')")
    @PostMapping("/{id}/approve")
    public ApiResponse<LeaveView> approve(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                          @Valid @RequestBody(required = false) LeaveDecisionRequest request) {
        return ApiResponse.ok(leaveService.approve(user, id, request == null ? null : request.comment()));
    }

    @Operation(summary = "驳回请假", description = "任课教师或管理员")
    @PreAuthorize("hasAuthority('leave:review') or hasAuthority('leave:manage_all')")
    @PostMapping("/{id}/reject")
    public ApiResponse<LeaveView> reject(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                         @Valid @RequestBody(required = false) LeaveDecisionRequest request) {
        return ApiResponse.ok(leaveService.reject(user, id, request == null ? null : request.comment()));
    }

    @Operation(summary = "撤销请假", description = "仅申请人本人，且申请仍为 PENDING")
    @PreAuthorize("hasAuthority('leave:submit')")
    @PostMapping("/{id}/cancel")
    public ApiResponse<LeaveView> cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                         @Valid @RequestBody(required = false) LeaveDecisionRequest request) {
        return ApiResponse.ok(leaveService.cancel(user, id, request == null ? null : request.comment()));
    }

    @Operation(summary = "销假", description = "申请人本人；仅已通过的请假，且只能销一次")
    @PreAuthorize("hasAuthority('leave:submit')")
    @PostMapping("/{id}/revoke")
    public ApiResponse<LeaveView> revoke(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                         @Valid @RequestBody(required = false) LeaveRevokeRequest request) {
        return ApiResponse.ok(leaveService.revoke(user, id, request == null ? null : request.note()));
    }

    @Operation(summary = "班级导员列表", description = "需要 leave:manage_all")
    @PreAuthorize("hasAuthority('leave:manage_all')")
    @GetMapping("/counselors")
    public ApiResponse<List<ClassCounselorView>> counselors(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(leaveService.allCounselors(user));
    }

    @Operation(summary = "配置班级导员", description = "需要 leave:manage_all；传空数组表示清空")
    @PreAuthorize("hasAuthority('leave:manage_all')")
    @PutMapping("/counselors/{classId}")
    public ApiResponse<ClassCounselorView> setCounselors(@AuthenticationPrincipal AuthenticatedUser user,
                                                         @PathVariable long classId,
                                                         @Valid @RequestBody ClassCounselorRequest request) {
        return ApiResponse.ok(leaveService.setCounselors(user, classId, request.counselorIds()));
    }
}
