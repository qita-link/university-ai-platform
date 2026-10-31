package com.campus.platform.classroom.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.classroom.entity.RoomBooking;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RoomBookingMapper extends BaseMapper<RoomBooking> {

    /** 已批准的借用是否与目标时段重叠（审批时再查一次，防并发）。 */
    @Select("""
            SELECT * FROM room_booking
             WHERE room_id = #{roomId} AND booking_date = #{date} AND status = 'APPROVED'
               AND start_period <= #{endPeriod} AND end_period >= #{startPeriod}
               AND id <> #{excludeId}
            """)
    List<RoomBooking> selectApprovedOverlap(@Param("roomId") long roomId, @Param("date") LocalDate date,
                                            @Param("startPeriod") int startPeriod,
                                            @Param("endPeriod") int endPeriod,
                                            @Param("excludeId") long excludeId);

    /** 审批：条件更新保证并发下只有一个人给出结论。 */
    @Update("""
            UPDATE room_booking
               SET status = #{status}, reviewer_id = #{reviewerId}, review_comment = #{comment},
                   reviewed_at = #{now}, updated_by = #{reviewerId}, updated_at = #{now},
                   version = version + 1
             WHERE id = #{id} AND status = 'PENDING'
            """)
    int decide(@Param("id") long id, @Param("status") String status, @Param("reviewerId") long reviewerId,
               @Param("comment") String comment, @Param("now") LocalDateTime now);

    /** 申请人取消：只能取消本人且仍待审批的借用。 */
    @Update("""
            UPDATE room_booking
               SET status = 'CANCELLED', review_comment = #{comment}, reviewed_at = #{now},
                   updated_by = #{applicantId}, updated_at = #{now}, version = version + 1
             WHERE id = #{id} AND applicant_id = #{applicantId} AND status = 'PENDING'
            """)
    int cancel(@Param("id") long id, @Param("applicantId") long applicantId,
               @Param("comment") String comment, @Param("now") LocalDateTime now);
}
