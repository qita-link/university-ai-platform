package com.campus.platform.attendance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.attendance.entity.AttendanceRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AttendanceRecordMapper extends BaseMapper<AttendanceRecord> {

    @Select("SELECT * FROM attendance_record WHERE session_id = #{sessionId} ORDER BY student_id")
    List<AttendanceRecord> selectBySession(@Param("sessionId") long sessionId);

    @Select("""
            <script>
            SELECT * FROM attendance_record
             WHERE session_id IN
            <foreach collection="sessionIds" item="id" open="(" separator="," close=")">#{id}</foreach>
             ORDER BY session_id, student_id
            </script>
            """)
    List<AttendanceRecord> selectBySessions(@Param("sessionIds") java.util.Collection<Long> sessionIds);

    @Delete("DELETE FROM attendance_record WHERE session_id = #{sessionId}")
    int deleteBySession(@Param("sessionId") long sessionId);

    /** 某学生某课程的累计旷课次数（缺勤预警用）。 */
    @Select("""
            SELECT COUNT(*)
              FROM attendance_record r
              JOIN attendance_session s ON s.id = r.session_id
             WHERE r.student_id = #{studentId} AND s.course_id = #{courseId} AND r.status = 'ABSENT'
            """)
    long countAbsent(@Param("studentId") long studentId, @Param("courseId") long courseId);

    /** 申诉通过后改明细状态。 */
    @Update("""
            UPDATE attendance_record
               SET status = #{status}, remark = #{remark}, updated_at = #{now}
             WHERE id = #{id}
            """)
    int updateStatus(@Param("id") long id, @Param("status") String status,
                     @Param("remark") String remark, @Param("now") java.time.LocalDateTime now);
}
