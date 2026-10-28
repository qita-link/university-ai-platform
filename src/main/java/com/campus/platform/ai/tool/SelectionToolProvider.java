package com.campus.platform.ai.tool;

import com.campus.platform.course.dto.SelectionView;
import com.campus.platform.course.entity.Course;
import com.campus.platform.course.service.CourseSelectionService;
import com.campus.platform.course.service.CourseService;
import com.campus.platform.security.Permissions;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.campus.platform.ai.tool.ToolParameter.required;

/**
 * 选课工具：选课与退课（均为写操作，需确认）。
 * <p>
 * 窗口、容量、重复、时间冲突等规则全部由 {@link CourseSelectionService} 判断，
 * 工具只负责参数整形与“目标与影响”预览。
 */
@Component
public class SelectionToolProvider implements AiToolProvider {

    private final CourseSelectionService selectionService;
    private final CourseService courseService;

    public SelectionToolProvider(CourseSelectionService selectionService, CourseService courseService) {
        this.selectionService = selectionService;
        this.courseService = courseService;
    }

    @Override
    public List<AiTool> tools() {
        return List.of(selectCourse(), dropCourse());
    }

    private AiTool selectCourse() {
        return SimpleAiTool.write("selectCourse", "选课",
                "为本人选择一门课程。仅在本学期选课窗口内、课程开放且未满、无时间冲突时成功。写操作，需用户确认。",
                List.of(Permissions.SELECTION_SELECT),
                List.of(required("courseId", "integer", "课程 ID")),
                (user, args) -> {
                    Course course = courseService.requireCourse(args.requireLong("courseId"));
                    return new ToolPreview("将选择课程《" + course.getCode() + " " + course.getName()
                            + "》，当前 " + course.getSelectedCount() + "/" + course.getCapacity()
                            + " 人；确认后占用一个名额");
                },
                (user, args) -> {
                    SelectionView selection = selectionService.select(user, args.requireLong("courseId"));
                    return new ToolResult(selection,
                            "已选课《" + selection.courseName() + "》（选课记录 " + selection.id() + "）");
                });
    }

    private AiTool dropCourse() {
        return SimpleAiTool.write("dropCourse", "退课",
                "退掉本人的一条有效选课记录并释放名额。仅在本学期选课窗口内可退。写操作，需用户确认。",
                List.of(Permissions.SELECTION_SELECT),
                List.of(required("selectionId", "integer", "选课记录 ID（来自 getMySchedule 或选课结果）")),
                (user, args) -> {
                    CourseSelectionService.SelectionTarget target =
                            selectionService.requireOwnSelection(user, args.requireLong("selectionId"));
                    return new ToolPreview("将退掉课程《" + target.courseCode() + " " + target.courseName()
                            + "》（选课记录 " + target.id() + "），并释放一个名额");
                },
                (user, args) -> {
                    long selectionId = args.requireLong("selectionId");
                    CourseSelectionService.SelectionTarget target = selectionService.requireOwnSelection(user, selectionId);
                    selectionService.drop(user, selectionId);
                    return new ToolResult(null, "已退课《" + target.courseName() + "》，名额已释放");
                });
    }
}
