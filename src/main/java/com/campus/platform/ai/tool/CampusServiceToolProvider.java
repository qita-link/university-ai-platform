package com.campus.platform.ai.tool;

import com.campus.platform.classroom.dto.FreeSlotView;
import com.campus.platform.classroom.dto.RoomView;
import com.campus.platform.classroom.service.ClassroomService;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.grade.dto.TranscriptView;
import com.campus.platform.grade.service.GradeService;
import com.campus.platform.security.Permissions;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import static com.campus.platform.ai.tool.ToolParameter.optional;

/**
 * 校务查询工具：空教室与本人成绩。
 * <p>
 * 两个都是只读工具，调用即执行、不需要确认；写操作（录成绩、登记考勤）一律只走 REST，
 * 不让模型直接改教务数据。
 */
@Component
public class CampusServiceToolProvider implements AiToolProvider {

    private final ClassroomService classroomService;
    private final GradeService gradeService;

    public CampusServiceToolProvider(ClassroomService classroomService, GradeService gradeService) {
        this.classroomService = classroomService;
        this.gradeService = gradeService;
    }

    @Override
    public List<AiTool> tools() {
        return List.of(freeClassrooms(), myGrades());
    }

    private AiTool freeClassrooms() {
        return SimpleAiTool.readOnly("findFreeClassrooms", "查询空教室",
                "查询某天某几节课的空闲教室。不传日期取今天，不传节次取当前节次。只读。",
                List.of(Permissions.CLASSROOM_READ),
                List.of(optional("date", "string", "日期，格式 yyyy-MM-dd，为空表示今天"),
                        optional("startPeriod", "integer", "开始节次 1~12，为空表示当前节次"),
                        optional("endPeriod", "integer", "结束节次 1~12"),
                        optional("minCapacity", "integer", "最少座位数")),
                (user, args) -> {
                    String raw = args.optionalString("date", 10);
                    LocalDate date;
                    try {
                        date = raw == null || raw.isBlank() ? null : LocalDate.parse(raw.trim());
                    } catch (DateTimeParseException e) {
                        throw BusinessException.badRequest("日期格式应为 yyyy-MM-dd");
                    }
                    FreeSlotView slot = classroomService.free(date,
                            args.optionalInt("startPeriod", null, 1, 12),
                            args.optionalInt("endPeriod", null, 1, 12),
                            args.optionalInt("minCapacity", null, 1, 5000),
                            null, null);
                    List<Map<String, Object>> rooms = slot.rooms().stream().limit(20)
                            .map(this::room)
                            .toList();
                    String answer = slot.date() + " 第 " + slot.startPeriod() + "–" + slot.endPeriod()
                            + " 节（" + slot.startTime() + "–" + slot.endTime() + "）共 "
                            + slot.rooms().size() + "/" + slot.totalRooms() + " 间空教室。";
                    return new ToolResult(Map.of(
                            "date", slot.date().toString(),
                            "dayOfWeek", slot.dayOfWeek(),
                            "weekNo", slot.weekNo(),
                            "startPeriod", slot.startPeriod(),
                            "endPeriod", slot.endPeriod(),
                            "freeCount", slot.rooms().size(),
                            "totalRooms", slot.totalRooms(),
                            "rooms", rooms), answer);
                });
    }

    private AiTool myGrades() {
        return SimpleAiTool.readOnly("getMyGrades", "查询我的成绩与绩点",
                "查询本人已发布的课程成绩、平均学分绩点与加权平均分。只读。",
                List.of(Permissions.GRADE_READ),
                List.of(),
                (user, args) -> {
                    TranscriptView transcript = gradeService.transcript(user);
                    String answer = transcript.overall().courseCount() == 0
                            ? "还没有已发布的成绩。"
                            : "已修 " + transcript.overall().courseCount() + " 门，平均学分绩点 "
                            + transcript.overall().gpa() + "，加权平均分 "
                            + transcript.overall().weightedAverage() + "。";
                    return new ToolResult(transcript, answer);
                });
    }

    private Map<String, Object> room(RoomView room) {
        return Map.of(
                "name", room.name(),
                "building", room.building() == null ? "" : room.building(),
                "capacity", room.capacity() == null ? 0 : room.capacity(),
                "type", room.roomType() == null ? "" : room.roomType());
    }
}
