package com.campus.platform.ai.tool;

import com.campus.platform.course.dto.AvailableCourseView;
import com.campus.platform.course.dto.CourseCreateRequest;
import com.campus.platform.course.dto.CourseDetailView;
import com.campus.platform.course.dto.CourseUpdateRequest;
import com.campus.platform.course.dto.MyScheduleItem;
import com.campus.platform.course.dto.ScheduleRequest;
import com.campus.platform.course.dto.ScheduleView;
import com.campus.platform.course.entity.Course;
import com.campus.platform.course.entity.Semester;
import com.campus.platform.course.entity.WeekParity;
import com.campus.platform.course.service.CourseScheduleService;
import com.campus.platform.course.service.CourseService;
import com.campus.platform.course.service.SemesterService;
import com.campus.platform.security.Permissions;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

import static com.campus.platform.ai.tool.ToolParameter.optional;
import static com.campus.platform.ai.tool.ToolParameter.required;

/**
 * 课程与排课工具：课表、可选课程、课程详情（只读）以及建课/改课/排课（写操作，需确认）。
 */
@Component
public class CourseToolProvider implements AiToolProvider {

    private final CourseService courseService;
    private final CourseScheduleService scheduleService;
    private final SemesterService semesterService;

    public CourseToolProvider(CourseService courseService, CourseScheduleService scheduleService,
                              SemesterService semesterService) {
        this.courseService = courseService;
        this.scheduleService = scheduleService;
        this.semesterService = semesterService;
    }

    @Override
    public List<AiTool> tools() {
        return List.of(mySchedule(), availableCourses(), courseDetails(),
                createCourse(), updateCourse(), updateSchedule());
    }

    private AiTool mySchedule() {
        return SimpleAiTool.readOnly("getMySchedule", "查询我的课表",
                "查询本人课表：学生返回已选课程的排课，教师返回任教课程的排课，按星期与节次排序。只读。",
                List.of(Permissions.COURSE_READ),
                List.of(optional("semesterId", "integer", "学期 ID；为空表示所有学期")),
                (user, args) -> {
                    List<MyScheduleItem> items = scheduleService.mySchedule(user, args.optionalLong("semesterId"));
                    return new ToolResult(items, "返回 " + items.size() + " 条排课");
                });
    }

    private AiTool availableCourses() {
        return SimpleAiTool.readOnly("searchAvailableCourses", "查询可选课程",
                "查询某学期可选课程，并给出是否可选与不可选原因（窗口关闭/未开放/已选/已满/时间冲突）。只读。",
                List.of(Permissions.COURSE_READ),
                List.of(required("semesterId", "integer", "学期 ID"),
                        optional("keyword", "string", "课程编码或名称关键词")),
                (user, args) -> {
                    List<AvailableCourseView> items = courseService.listAvailable(
                            user, args.requireLong("semesterId"), args.optionalString("keyword", 100));
                    long selectable = items.stream().filter(AvailableCourseView::selectable).count();
                    return new ToolResult(items, "返回 " + items.size() + " 门课程，其中 " + selectable + " 门可选");
                });
    }

    private AiTool courseDetails() {
        return SimpleAiTool.readOnly("getCourseDetails", "查询课程详情",
                "查询课程详情：授课教师、排课、容量与已选人数、本人选课状态。已停开课程对普通用户不可见。只读。",
                List.of(Permissions.COURSE_READ),
                List.of(required("courseId", "integer", "课程 ID")),
                (user, args) -> {
                    CourseDetailView course = courseService.get(user, args.requireLong("courseId"));
                    return new ToolResult(course, "《" + course.name() + "》" + course.status()
                            + "，" + course.selectedCount() + "/" + course.capacity() + " 人");
                });
    }

    private AiTool createCourse() {
        return SimpleAiTool.write("createCourse", "创建课程",
                "在指定学期创建课程，创建人自动成为主讲。学期必须未结束，课程编码在学期内唯一。写操作，需用户确认。",
                List.of(Permissions.COURSE_MANAGE_OWN, Permissions.COURSE_MANAGE_ALL),
                List.of(required("semesterId", "integer", "学期 ID"),
                        required("code", "string", "课程编码，学期内唯一"),
                        required("name", "string", "课程名称"),
                        required("credit", "number", "学分 0~20"),
                        required("capacity", "integer", "容量 1~1000"),
                        optional("description", "string", "课程简介"),
                        optional("orgUnitId", "integer", "开课组织；为空取本人组织")),
                (user, args) -> {
                    Semester semester = semesterService.requireNotClosed(args.requireLong("semesterId"));
                    return new ToolPreview("将在学期「" + semester.getName() + "」创建课程《"
                            + args.requireString("name", 128) + "》（编码 " + args.requireString("code", 32)
                            + "，容量 " + args.requireInt("capacity", 1, 1000) + " 人），创建人成为主讲");
                },
                (user, args) -> {
                    CourseDetailView created = courseService.create(user, new CourseCreateRequest(
                            args.requireLong("semesterId"),
                            args.requireString("code", 32),
                            args.requireString("name", 128),
                            BigDecimal.valueOf(args.requireDouble("credit", 0, 20)),
                            args.optionalString("description", 1000),
                            args.optionalLong("orgUnitId"),
                            args.requireInt("capacity", 1, 1000)));
                    return new ToolResult(created, "已创建课程《" + created.name() + "》（ID " + created.id() + "）");
                });
    }

    private AiTool updateCourse() {
        return SimpleAiTool.write("updateCourse", "修改课程",
                "修改课程名称、学分、简介、容量与开课组织（学期与编码不可改）。需要主讲教师或管理员身份，并回传当前 version。写操作，需用户确认。",
                List.of(Permissions.COURSE_MANAGE_OWN, Permissions.COURSE_MANAGE_ALL),
                List.of(required("courseId", "integer", "课程 ID"),
                        required("name", "string", "课程名称"),
                        required("credit", "number", "学分 0~20"),
                        required("capacity", "integer", "容量，不能小于已选人数"),
                        required("version", "integer", "当前版本号，从课程详情获取"),
                        optional("description", "string", "课程简介"),
                        optional("orgUnitId", "integer", "开课组织")),
                (user, args) -> {
                    Course course = courseService.requireManageable(user, args.requireLong("courseId"));
                    return new ToolPreview("将修改课程《" + course.getCode() + " " + course.getName()
                            + "》：名称改为「" + args.requireString("name", 128) + "」，容量 "
                            + course.getSelectedCount() + "/" + course.getCapacity() + " → "
                            + args.requireInt("capacity", 1, 1000) + " 人（当前版本 v" + course.getVersion() + "）");
                },
                (user, args) -> {
                    CourseDetailView updated = courseService.update(user, args.requireLong("courseId"),
                            new CourseUpdateRequest(
                                    args.requireString("name", 128),
                                    BigDecimal.valueOf(args.requireDouble("credit", 0, 20)),
                                    args.optionalString("description", 1000),
                                    args.optionalLong("orgUnitId"),
                                    args.requireInt("capacity", 1, 1000),
                                    args.requireInt("version", 0, Integer.MAX_VALUE)));
                    return new ToolResult(updated, "已修改课程《" + updated.name() + "》（v" + updated.version() + "）");
                });
    }

    private AiTool updateSchedule() {
        return SimpleAiTool.write("updateCourseSchedule", "排课",
                "为课程新增或修改一条排课（星期/节次/周次/单双周/教室）。传 scheduleId 表示修改，否则新增。"
                        + "会校验本课程与授课教师的时间冲突。需要主讲教师或管理员身份。写操作，需用户确认。",
                List.of(Permissions.COURSE_MANAGE_OWN, Permissions.COURSE_MANAGE_ALL),
                List.of(required("courseId", "integer", "课程 ID"),
                        optional("scheduleId", "integer", "已有排课 ID；为空表示新增"),
                        required("dayOfWeek", "integer", "星期 1~7"),
                        required("startPeriod", "integer", "开始节次 1~12"),
                        required("endPeriod", "integer", "结束节次 1~12"),
                        required("startWeek", "integer", "开始周 1~30"),
                        required("endWeek", "integer", "结束周 1~30"),
                        optional("weekParity", "enum", "ALL/ODD/EVEN，默认 ALL"),
                        optional("classroom", "string", "教室")),
                (user, args) -> {
                    Course course = courseService.requireManageable(user, args.requireLong("courseId"));
                    Long scheduleId = args.optionalLong("scheduleId");
                    return new ToolPreview((scheduleId == null ? "将为课程《" : "将修改课程《")
                            + course.getCode() + " " + course.getName() + "》的排课：周"
                            + args.requireInt("dayOfWeek", 1, 7) + " 第"
                            + args.requireInt("startPeriod", 1, 12) + "-"
                            + args.requireInt("endPeriod", 1, 12) + " 节，第"
                            + args.requireInt("startWeek", 1, 30) + "-"
                            + args.requireInt("endWeek", 1, 30) + " 周（"
                            + (args.optionalString("weekParity", 8) == null ? "ALL"
                            : args.requireEnum("weekParity", List.of("ALL", "ODD", "EVEN")))
                            + "），教室 " + (args.optionalString("classroom", 64) == null ? "未指定"
                            : args.optionalString("classroom", 64)));
                },
                (user, args) -> {
                    long courseId = args.requireLong("courseId");
                    ScheduleRequest request = new ScheduleRequest(
                            args.requireInt("dayOfWeek", 1, 7),
                            args.requireInt("startPeriod", 1, 12),
                            args.requireInt("endPeriod", 1, 12),
                            args.requireInt("startWeek", 1, 30),
                            args.requireInt("endWeek", 1, 30),
                            args.optionalString("weekParity", 8) == null ? WeekParity.ALL
                                    : WeekParity.valueOf(args.requireEnum("weekParity",
                                    List.of("ALL", "ODD", "EVEN"))),
                            args.optionalString("classroom", 64));
                    Long scheduleId = args.optionalLong("scheduleId");
                    List<ScheduleView> schedules = scheduleId == null
                            ? scheduleService.add(user, courseId, request)
                            : scheduleService.update(user, courseId, scheduleId, request);
                    return new ToolResult(schedules, (scheduleId == null ? "已新增排课，当前共 " : "已修改排课，当前共 ")
                            + schedules.size() + " 条");
                });
    }

}
