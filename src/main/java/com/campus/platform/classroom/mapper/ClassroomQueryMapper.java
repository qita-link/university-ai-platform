package com.campus.platform.classroom.mapper;

import com.campus.platform.classroom.dto.RoomOccupancyRow;
import com.campus.platform.classroom.dto.ScheduleOccupancyRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 空教室查询所需的占用数据。只读，不写任何排课结果。
 */
@Mapper
public interface ClassroomQueryMapper {

    /**
     * 已发布课表（CP-SAT 求解结果）占用的教室与时段。
     * 只取 PUBLISHED 版本，草稿调整不影响当前空教室判断。
     */
    @Select("""
            SELECT a.room_id      AS room_id,
                   r.code         AS room_code,
                   r.name         AS room_name,
                   a.day_of_week  AS day_of_week,
                   a.start_period AS start_period,
                   a.end_period   AS end_period,
                   t.start_week   AS start_week,
                   t.end_week     AS end_week,
                   t.week_parity  AS week_parity
              FROM sch_assignment a
              JOIN sch_version v ON v.id = a.version_id AND v.status = 'PUBLISHED'
              JOIN sch_task t    ON t.id = a.task_id
              LEFT JOIN sch_room r ON r.id = a.room_id
             WHERE v.semester_id = #{semesterId}
               AND a.room_id IS NOT NULL
               AND a.day_of_week IS NOT NULL
            """)
    List<RoomOccupancyRow> selectPublishedAssignments(@Param("semesterId") long semesterId);

    /**
     * 课程模块自身的排课表占用的教室（教室为自由文本，可能没登记在 sch_room 中）。
     */
    @Select("""
            SELECT cs.classroom   AS classroom,
                   cs.day_of_week AS day_of_week,
                   cs.start_period AS start_period,
                   cs.end_period   AS end_period,
                   cs.start_week   AS start_week,
                   cs.end_week     AS end_week,
                   cs.week_parity  AS week_parity
              FROM course_schedule cs
              JOIN course c ON c.id = cs.course_id
             WHERE c.semester_id = #{semesterId}
               AND c.status <> 'CANCELLED'
               AND cs.classroom IS NOT NULL
               AND cs.classroom <> ''
            """)
    List<ScheduleOccupancyRow> selectCourseSchedules(@Param("semesterId") long semesterId);
}
