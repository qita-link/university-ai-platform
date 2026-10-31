package com.campus.platform.timetable.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 从自动排课（V8）读取“我的”已发布课表。
 * <p>
 * 只读：本模块不写 {@code sch_*} 表。教师按 {@code sch_task_teacher} 命中，
 * 学生按所在行政班（{@code sch_teaching_class_member.org_unit_id}）命中。
 */
@Mapper
public interface TimetableMapper {

    /**
     * @param teacherId  教师 ID，学生传 null
     * @param classId    学生所在行政班 ID，教师传 null
     * @param semesterId 为空表示不限学期
     */
    @Select("""
            <script>
            SELECT a.id              AS assignmentId,
                   t.id              AS taskId,
                   t.semester_id     AS semesterId,
                   t.linked_course_id AS courseId,
                   sc.code           AS courseCode,
                   sc.name           AS courseName,
                   a.day_of_week     AS dayOfWeek,
                   a.start_period    AS startPeriod,
                   a.end_period      AS endPeriod,
                   t.start_week      AS startWeek,
                   t.end_week        AS endWeek,
                   t.week_parity     AS weekParity,
                   r.name            AS roomName,
                   (SELECT GROUP_CONCAT(u.display_name ORDER BY tt.teacher_role SEPARATOR '、')
                      FROM sch_task_teacher tt
                      JOIN sys_user u ON u.id = tt.teacher_id
                     WHERE tt.task_id = t.id) AS teacherNames
            FROM sch_assignment a
            JOIN sch_version v ON v.id = a.version_id AND v.status = 'PUBLISHED'
            JOIN sch_task t    ON t.id = a.task_id
            JOIN sch_course sc ON sc.id = t.course_id
            LEFT JOIN sch_room r ON r.id = a.room_id
            WHERE a.day_of_week IS NOT NULL
              AND a.start_period IS NOT NULL
              <if test="semesterId != null">AND t.semester_id = #{semesterId}</if>
              AND (
                <if test="teacherId != null">
                  EXISTS (SELECT 1 FROM sch_task_teacher tt2
                           WHERE tt2.task_id = t.id AND tt2.teacher_id = #{teacherId})
                </if>
                <if test="teacherId != null and classId != null">OR</if>
                <if test="classId != null">
                  EXISTS (SELECT 1 FROM sch_teaching_class_member m
                           WHERE m.teaching_class_id = t.teaching_class_id AND m.org_unit_id = #{classId})
                </if>
                <if test="teacherId == null and classId == null">1 = 0</if>
              )
            ORDER BY a.day_of_week, a.start_period
            </script>
            """)
    List<ScheduledRow> selectMyAssignments(@Param("teacherId") Long teacherId,
                                           @Param("classId") Long classId,
                                           @Param("semesterId") Long semesterId);

    /** 一行排课分配（扁平结构，由 Service 组装成课表条目）。 */
    record ScheduledRow(Long assignmentId, Long taskId, Long semesterId, Long courseId,
                        String courseCode, String courseName,
                        Integer dayOfWeek, Integer startPeriod, Integer endPeriod,
                        Integer startWeek, Integer endWeek, String weekParity,
                        String roomName, String teacherNames) {
    }
}
