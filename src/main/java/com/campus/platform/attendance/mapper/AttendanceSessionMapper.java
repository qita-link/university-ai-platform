package com.campus.platform.attendance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.attendance.entity.AttendanceSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AttendanceSessionMapper extends BaseMapper<AttendanceSession> {

    @Select("""
            SELECT * FROM attendance_session
             WHERE course_id = #{courseId} AND class_id = #{classId}
               AND session_date = #{sessionDate} AND start_period = #{startPeriod}
            """)
    AttendanceSession selectSlot(@Param("courseId") long courseId, @Param("classId") long classId,
                                 @Param("sessionDate") java.time.LocalDate sessionDate,
                                 @Param("startPeriod") int startPeriod);
}
