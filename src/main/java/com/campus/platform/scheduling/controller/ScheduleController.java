package com.campus.platform.scheduling.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.scheduling.dto.JobDtos;
import com.campus.platform.scheduling.dto.ScheduleDtos;
import com.campus.platform.scheduling.dto.TermDtos;
import com.campus.platform.scheduling.service.ScheduleAdjustService;
import com.campus.platform.scheduling.service.SchedulePublishService;
import com.campus.platform.scheduling.service.ScheduleQueryService;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 课表版本、查询、调整、发布、导出与本人课表。
 */
@Tag(name = "排课-课表与发布", description = "版本查询/对比/复制、课表视图、未安排与评分、调整、局部重排、发布、导出")
@RestController
@RequestMapping("/api/v1/scheduling")
public class ScheduleController {

    private final ScheduleQueryService queryService;
    private final ScheduleAdjustService adjustService;
    private final SchedulePublishService publishService;

    public ScheduleController(ScheduleQueryService queryService, ScheduleAdjustService adjustService,
                              SchedulePublishService publishService) {
        this.queryService = queryService;
        this.adjustService = adjustService;
        this.publishService = publishService;
    }

    // ------------------------------------------------------------------ 版本

    @Operation(summary = "版本列表")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/versions")
    public ApiResponse<List<ScheduleDtos.VersionView>> versions(@AuthenticationPrincipal AuthenticatedUser user,
                                                                @RequestParam(required = false) Long semesterId) {
        return ApiResponse.ok(queryService.listVersions(user, semesterId));
    }

    @Operation(summary = "版本详情")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/versions/{versionId}")
    public ApiResponse<ScheduleDtos.VersionView> version(@AuthenticationPrincipal AuthenticatedUser user,
                                                         @PathVariable long versionId) {
        return ApiResponse.ok(queryService.getVersion(user, versionId));
    }

    @Operation(summary = "复制版本为新草稿", description = "不改动原版本（包括已发布版本）")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PostMapping("/versions/{versionId}/copy")
    public ApiResponse<ScheduleDtos.VersionView> copy(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @PathVariable long versionId,
                                                      @RequestBody(required = false) ScheduleDtos.CopyRequest req) {
        return ApiResponse.ok(queryService.copy(user, versionId,
                req == null ? new ScheduleDtos.CopyRequest(null, null) : req));
    }

    @Operation(summary = "版本对比")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/versions/{versionId}/compare")
    public ApiResponse<ScheduleDtos.CompareView> compare(@AuthenticationPrincipal AuthenticatedUser user,
                                                         @PathVariable long versionId,
                                                         @RequestParam long otherVersionId) {
        return ApiResponse.ok(queryService.compare(user, versionId, otherVersionId));
    }

    // ------------------------------------------------------------------ 课表

    @Operation(summary = "课时列表", description = "可按教师、班级、教室、课程、星期、节次、周次过滤")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/versions/{versionId}/entries")
    public ApiResponse<List<ScheduleDtos.EntryView>> entries(@AuthenticationPrincipal AuthenticatedUser user,
                                                             @PathVariable long versionId,
                                                             @RequestParam(required = false) Long teacherId,
                                                             @RequestParam(required = false) Long classId,
                                                             @RequestParam(required = false) Long roomId,
                                                             @RequestParam(required = false) Long courseId,
                                                             @RequestParam(required = false) Integer dayOfWeek,
                                                             @RequestParam(required = false) Integer period,
                                                             @RequestParam(required = false) Integer week) {
        return ApiResponse.ok(queryService.entries(user, versionId, new ScheduleQueryService.EntryFilter(
                teacherId, classId, roomId, courseId, dayOfWeek, period, week, false)));
    }

    @Operation(summary = "课表视图（星期 × 节次）", description = "按教师/班级/教室维度组织")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/versions/{versionId}/timetable")
    public ApiResponse<ScheduleDtos.TimetableView> timetable(@AuthenticationPrincipal AuthenticatedUser user,
                                                             @PathVariable long versionId,
                                                             @RequestParam(required = false) Long teacherId,
                                                             @RequestParam(required = false) Long classId,
                                                             @RequestParam(required = false) Long roomId) {
        return ApiResponse.ok(queryService.timetable(user, versionId, teacherId, classId, roomId));
    }

    @Operation(summary = "未安排课程及原因")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/versions/{versionId}/unplaced")
    public ApiResponse<List<ScheduleDtos.UnplacedView>> unplaced(@AuthenticationPrincipal AuthenticatedUser user,
                                                                 @PathVariable long versionId) {
        return ApiResponse.ok(queryService.unplaced(user, versionId));
    }

    @Operation(summary = "评分明细与冲突（实时复核）")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/versions/{versionId}/evaluation")
    public ApiResponse<ScheduleDtos.EvaluationView> evaluation(@AuthenticationPrincipal AuthenticatedUser user,
                                                               @PathVariable long versionId) {
        return ApiResponse.ok(queryService.evaluation(user, versionId));
    }

    @Operation(summary = "硬约束冲突列表")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/versions/{versionId}/conflicts")
    public ApiResponse<List<ScheduleDtos.ConflictView>> conflicts(@AuthenticationPrincipal AuthenticatedUser user,
                                                                  @PathVariable long versionId) {
        return ApiResponse.ok(queryService.conflicts(user, versionId));
    }

    @Operation(summary = "整体复核并固化结果", description = "重写问题清单，更新已排课次/硬约束违反数/评分；需要 schedule:adjust")
    @PreAuthorize("hasAuthority('schedule:adjust')")
    @PostMapping("/versions/{versionId}/check")
    public ApiResponse<ScheduleDtos.EvaluationView> check(@AuthenticationPrincipal AuthenticatedUser user,
                                                          @PathVariable long versionId) {
        return ApiResponse.ok(adjustService.check(user, versionId));
    }

    @Operation(summary = "某节课的可选位置", description = "已排除与现有课表冲突的组合，最多 300 条")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/versions/{versionId}/entries/{entryId}/candidates")
    public ApiResponse<List<ScheduleDtos.CandidateView>> candidates(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long versionId,
            @PathVariable long entryId) {
        return ApiResponse.ok(adjustService.candidates(user, versionId, entryId));
    }

    // ------------------------------------------------------------------ 调整

    @Operation(summary = "调整单节课", description = "dayOfWeek 为空表示取消安排；冲突时返回 409 并给出冲突课程")
    @PreAuthorize("hasAuthority('schedule:adjust')")
    @PutMapping("/versions/{versionId}/entries/{entryId}")
    public ApiResponse<ScheduleDtos.EntryView> adjust(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @PathVariable long versionId, @PathVariable long entryId,
                                                      @Valid @RequestBody ScheduleDtos.AdjustRequest req) {
        return ApiResponse.ok(adjustService.adjust(user, versionId, entryId, req));
    }

    @Operation(summary = "锁定/解锁课时", description = "锁定后重新求解（FULL + keepLocked）会保留该位置")
    @PreAuthorize("hasAuthority('schedule:adjust')")
    @PutMapping("/versions/{versionId}/entries/{entryId}/lock")
    public ApiResponse<ScheduleDtos.EntryView> lock(@AuthenticationPrincipal AuthenticatedUser user,
                                                    @PathVariable long versionId, @PathVariable long entryId,
                                                    @Valid @RequestBody ScheduleDtos.LockRequest req) {
        return ApiResponse.ok(adjustService.lock(user, versionId, entryId, req));
    }

    @Operation(summary = "调整历史")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/versions/{versionId}/adjustments")
    public ApiResponse<List<ScheduleDtos.AdjustmentView>> adjustments(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long versionId) {
        return ApiResponse.ok(queryService.adjustments(user, versionId));
    }

    @Operation(summary = "局部重排", description = "以该版本为基准，只放开指定任务，其余课时固定；生成新版本")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PostMapping("/versions/{versionId}/reschedule")
    public ApiResponse<JobDtos.JobView> reschedule(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @PathVariable long versionId,
                                                   @RequestParam(required = false) List<Long> taskIds,
                                                   @RequestParam(required = false) Integer timeLimitSeconds,
                                                   @RequestParam(required = false) String reason) {
        return ApiResponse.ok(adjustService.reschedule(user, versionId, taskIds, timeLimitSeconds, reason));
    }

    // ------------------------------------------------------------------ 发布

    @Operation(summary = "发布课表", description = "发布前强制复核硬约束；同学期旧发布版本自动归档")
    @PreAuthorize("hasAuthority('schedule:publish')")
    @PostMapping("/versions/{versionId}/publish")
    public ApiResponse<ScheduleDtos.VersionView> publish(@AuthenticationPrincipal AuthenticatedUser user,
                                                         @PathVariable long versionId,
                                                         @RequestBody(required = false)
                                                         ScheduleDtos.PublishRequest req) {
        return ApiResponse.ok(publishService.publish(user, versionId, req));
    }

    @Operation(summary = "撤销发布", description = "撤销后该学期没有已发布课表")
    @PreAuthorize("hasAuthority('schedule:publish')")
    @PostMapping("/versions/{versionId}/unpublish")
    public ApiResponse<ScheduleDtos.VersionView> unpublish(@AuthenticationPrincipal AuthenticatedUser user,
                                                           @PathVariable long versionId,
                                                           @RequestBody(required = false)
                                                           ScheduleDtos.PublishRequest req) {
        return ApiResponse.ok(publishService.unpublish(user, versionId, req));
    }

    @Operation(summary = "发布记录")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/publish-records")
    public ApiResponse<List<ScheduleDtos.PublishRecordView>> publishRecords(
            @AuthenticationPrincipal AuthenticatedUser user, @RequestParam(required = false) Long semesterId) {
        return ApiResponse.ok(publishService.records(user, semesterId));
    }

    // ------------------------------------------------------------------ 导出与本人课表

    @Operation(summary = "导出课表 CSV", description = "UTF-8 BOM；可按教师/班级/教室过滤")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping(value = "/versions/{versionId}/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthenticatedUser user,
                                         @PathVariable long versionId,
                                         @RequestParam(required = false) Long teacherId,
                                         @RequestParam(required = false) Long classId,
                                         @RequestParam(required = false) Long roomId) {
        byte[] body = queryService.exportCsv(user, versionId, teacherId, classId, roomId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"timetable-" + versionId + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @Operation(summary = "本人课表", description = "教师看自己的已发布课表；学生看所在行政班的已发布课表")
    @PreAuthorize("hasAuthority('timetable:read')")
    @GetMapping("/timetables/me")
    public ApiResponse<ScheduleDtos.TimetableView> myTimetable(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) Long semesterId) {
        return ApiResponse.ok(queryService.myTimetable(user, semesterId));
    }

    @Operation(summary = "教室课表", description = "按教室查已发布课表：这一周这间教室上了哪些课。仅教师与管理员（学生无 schedule:preference:self）")
    @PreAuthorize("hasAnyAuthority('schedule:read','schedule:preference:self')")
    @GetMapping("/timetables/room")
    public ApiResponse<ScheduleDtos.TimetableView> roomTimetable(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam long roomId,
            @RequestParam(required = false) Long semesterId) {
        return ApiResponse.ok(queryService.roomTimetable(user, roomId, semesterId));
    }

    @Operation(summary = "节次作息", description = "课表左侧的时间列（第几节 几点到几点）；timetable:read 即可读，学期未配置作息时返回空数组")
    @PreAuthorize("hasAuthority('timetable:read')")
    @GetMapping("/timetables/periods")
    public ApiResponse<List<TermDtos.PeriodView>> timetablePeriods(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam long semesterId) {
        return ApiResponse.ok(queryService.periods(user, semesterId));
    }
}
