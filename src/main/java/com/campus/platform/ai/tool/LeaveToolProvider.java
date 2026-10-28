package com.campus.platform.ai.tool;

import com.campus.platform.common.api.PageResult;
import com.campus.platform.course.dto.LeaveQuery;
import com.campus.platform.course.dto.LeaveSubmitRequest;
import com.campus.platform.course.dto.LeaveView;
import com.campus.platform.course.entity.Course;
import com.campus.platform.course.entity.LeaveStatus;
import com.campus.platform.course.service.CourseService;
import com.campus.platform.course.service.LeaveRequestService;
import com.campus.platform.security.Permissions;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.campus.platform.ai.tool.ToolParameter.optional;
import static com.campus.platform.ai.tool.ToolParameter.required;

/**
 * 请假工具：查询本人请假（只读）、提交请假、审批请假（写操作，需确认）。
 */
@Component
public class LeaveToolProvider implements AiToolProvider {

    private static final List<String> STATUSES = List.of("PENDING", "APPROVED", "REJECTED", "CANCELLED");

    private final LeaveRequestService leaveService;
    private final CourseService courseService;

    public LeaveToolProvider(LeaveRequestService leaveService, CourseService courseService) {
        this.leaveService = leaveService;
        this.courseService = courseService;
    }

    @Override
    public List<AiTool> tools() {
        return List.of(myLeaves(), submitLeave(), reviewLeave());
    }

    private AiTool myLeaves() {
        return SimpleAiTool.readOnly("getMyLeaveRequests", "查询我的请假",
                "分页查询本人的请假记录（课程、时间、原因、状态、审批意见）。只读。",
                List.of(Permissions.LEAVE_SUBMIT),
                List.of(optional("status", "enum", "PENDING/APPROVED/REJECTED/CANCELLED，为空表示全部"),
                        optional("page", "integer", "页码，从 1 开始"),
                        optional("size", "integer", "每页条数 1~50，默认 10")),
                (user, args) -> {
                    LeaveQuery query = new LeaveQuery();
                    String status = args.optionalString("status", 16);
                    if (status != null) {
                        query.setStatus(LeaveStatus.valueOf(args.requireEnum("status", STATUSES)));
                    }
                    query.setPage(args.optionalInt("page", 1, 1, 100000));
                    query.setSize(args.optionalInt("size", 10, 1, 50));
                    PageResult<LeaveView> page = leaveService.myRequests(user, query);
                    return new ToolResult(page, "返回 " + page.items().size() + " 条，共 " + page.total() + " 条");
                });
    }

    private AiTool submitLeave() {
        return SimpleAiTool.write("submitLeaveRequest", "提交请假",
                "为本人已选课程提交请假申请，提交后状态为 PENDING，等待任课教师审批。时间必须落在学期范围内。写操作，需用户确认。",
                List.of(Permissions.LEAVE_SUBMIT),
                List.of(required("courseId", "integer", "课程 ID，必须是本人已选课程"),
                        required("startAt", "datetime", "开始时间，如 2026-10-28T10:00:00"),
                        required("endAt", "datetime", "结束时间"),
                        required("reason", "string", "请假原因"),
                        optional("scheduleId", "integer", "关联排课 ID，可为空")),
                (user, args) -> {
                    Course course = courseService.requireCourse(args.requireLong("courseId"));
                    return new ToolPreview("将为课程《" + course.getCode() + " " + course.getName() + "》提交请假："
                            + args.requireDateTime("startAt") + " ~ " + args.requireDateTime("endAt")
                            + "，原因：" + args.requireString("reason", 500));
                },
                (user, args) -> {
                    LeaveView leave = leaveService.submit(user, new LeaveSubmitRequest(
                            args.requireLong("courseId"),
                            args.optionalLong("scheduleId"),
                            args.requireDateTime("startAt"),
                            args.requireDateTime("endAt"),
                            args.requireString("reason", 500)));
                    return new ToolResult(leave, "已提交请假（" + leave.id() + "），状态 " + leave.status());
                });
    }

    private AiTool reviewLeave() {
        return SimpleAiTool.write("reviewLeaveRequest", "审批请假",
                "通过或驳回一条待审批的请假申请。只能审批本人任教课程的请假，管理员可审批全部。写操作，需用户确认。",
                List.of(Permissions.LEAVE_REVIEW, Permissions.LEAVE_MANAGE_ALL),
                List.of(required("leaveId", "integer", "请假申请 ID"),
                        required("decision", "enum", "APPROVED 通过 / REJECTED 驳回"),
                        optional("comment", "string", "审批意见")),
                (user, args) -> {
                    LeaveView leave = leaveService.get(user, args.requireLong("leaveId"));
                    String decision = args.requireEnum("decision", List.of("APPROVED", "REJECTED"));
                    return new ToolPreview(("APPROVED".equals(decision) ? "将通过 " : "将驳回 ")
                            + "学生 " + leave.studentName() + " 在《" + leave.courseName() + "》的请假申请（"
                            + leave.startAt() + " ~ " + leave.endAt() + "，当前状态 " + leave.status() + "）");
                },
                (user, args) -> {
                    long leaveId = args.requireLong("leaveId");
                    String decision = args.requireEnum("decision", List.of("APPROVED", "REJECTED"));
                    String comment = args.optionalString("comment", 500);
                    LeaveView leave = "APPROVED".equals(decision)
                            ? leaveService.approve(user, leaveId, comment)
                            : leaveService.reject(user, leaveId, comment);
                    return new ToolResult(leave, "已" + ("APPROVED".equals(decision) ? "通过" : "驳回")
                            + "请假（" + leave.id() + "），状态 " + leave.status());
                });
    }
}
