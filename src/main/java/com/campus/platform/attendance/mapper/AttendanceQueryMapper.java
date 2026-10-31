package com.campus.platform.attendance.mapper;

import com.campus.platform.attendance.dto.AbsenceCountRow;
import com.campus.platform.attendance.dto.MyAttendanceRow;
import com.campus.platform.attendance.dto.SessionCountRow;
import com.campus.platform.attendance.dto.StudentStatusCountRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 考勤统计查询：班级课表、场次与旷课人次。
 */
@Mapper
public interface AttendanceQueryMapper {

    /** 该班学生在指定学期实际选了课的课程（按选课名单反查）。 */
    @Select("""
            <script>
            SELECT DISTINCT c.id
              FROM course c
              JOIN course_selection cs ON cs.course_id = c.id AND cs.status = 'SELECTED'
              JOIN sys_user u ON u.id = cs.student_id
             WHERE u.org_unit_id = #{classId}
               <if test="semesterId != null"> AND c.semester_id = #{semesterId} </if>
             ORDER BY c.id
            </script>
            """)
    List<Long> selectCourseIdsByClass(@Param("classId") long classId,
                                      @Param("semesterId") Long semesterId);

    @Select("""
            <script>
            SELECT s.course_id AS course_id, s.schedule_id AS schedule_id, COUNT(*) AS session_count
              FROM attendance_session s
              JOIN course c ON c.id = s.course_id
             WHERE s.class_id = #{classId}
               <if test="semesterId != null"> AND c.semester_id = #{semesterId} </if>
             GROUP BY s.course_id, s.schedule_id
            </script>
            """)
    List<SessionCountRow> selectSessionCounts(@Param("classId") long classId,
                                             @Param("semesterId") Long semesterId);

    @Select("""
            <script>
            SELECT s.course_id AS course_id, s.schedule_id AS schedule_id, COUNT(*) AS absent_count
              FROM attendance_record r
              JOIN attendance_session s ON s.id = r.session_id
              JOIN course c ON c.id = s.course_id
             WHERE s.class_id = #{classId} AND r.status = 'ABSENT'
               <if test="semesterId != null"> AND c.semester_id = #{semesterId} </if>
             GROUP BY s.course_id, s.schedule_id
            </script>
            """)
    List<AbsenceCountRow> selectAbsenceCounts(@Param("classId") long classId,
                                              @Param("semesterId") Long semesterId);

    @Select("""
            <script>
            SELECT r.student_id AS student_id, r.status AS status, COUNT(*) AS cnt
              FROM attendance_record r
              JOIN attendance_session s ON s.id = r.session_id
              JOIN course c ON c.id = s.course_id
             WHERE s.class_id = #{classId}
               <if test="semesterId != null"> AND c.semester_id = #{semesterId} </if>
               <if test="courseId != null"> AND s.course_id = #{courseId} </if>
             GROUP BY r.student_id, r.status
            </script>
            """)
    List<StudentStatusCountRow> selectStudentCounts(@Param("classId") long classId,
                                                    @Param("semesterId") Long semesterId,
                                                    @Param("courseId") Long courseId);

    /** 学生本人的考勤明细（含申诉状态），用于「我的考勤」。 */
    @Select("""
            <script>
            SELECT r.id AS record_id, r.status AS status, r.remark AS remark,
                   s.id AS session_id, s.session_date AS session_date,
                   s.start_period AS start_period, s.end_period AS end_period,
                   c.id AS course_id, c.code AS course_code, c.name AS course_name,
                   a.id AS appeal_id, a.status AS appeal_status
              FROM attendance_record r
              JOIN attendance_session s ON s.id = r.session_id
              JOIN course c ON c.id = s.course_id
              LEFT JOIN attendance_appeal a ON a.record_id = r.id
             WHERE r.student_id = #{studentId}
               <if test="status != null"> AND r.status = #{status} </if>
             ORDER BY s.session_date DESC, s.start_period DESC
             LIMIT #{limit}
            </script>
            """)
    List<MyAttendanceRow> selectStudentRecords(@Param("studentId") long studentId,
                                               @Param("status") String status,
                                               @Param("limit") int limit);
}
