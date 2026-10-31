package com.campus.platform.attendance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.attendance.entity.AttendanceAppeal;
import com.campus.platform.attendance.entity.AttendanceRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AttendanceAppealMapper extends BaseMapper<AttendanceAppeal> {

    @Select("SELECT * FROM attendance_appeal WHERE record_id = #{recordId}")
    AttendanceAppeal selectByRecord(@Param("recordId") long recordId);

    /** 审核：条件更新保证并发下只有一个人给出结论。 */
    @Update("""
            UPDATE attendance_appeal
               SET status = #{status}, reviewer_id = #{reviewerId}, review_comment = #{comment},
                   reviewed_at = #{now}, updated_by = #{reviewerId}, updated_at = #{now},
                   version = version + 1
             WHERE id = #{id} AND status = 'PENDING'
            """)
    int decide(@Param("id") long id, @Param("status") String status, @Param("reviewerId") long reviewerId,
               @Param("comment") String comment, @Param("now") LocalDateTime now);
}
