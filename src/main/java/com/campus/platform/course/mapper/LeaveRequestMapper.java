package com.campus.platform.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.course.entity.LeaveRequest;
import com.campus.platform.course.entity.LeaveStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface LeaveRequestMapper extends BaseMapper<LeaveRequest> {

    /** 申请人撤销：只能撤销本人且仍为 PENDING 的申请。 */
    @Update("""
            UPDATE leave_request
               SET status = 'CANCELLED', review_comment = #{comment}, reviewed_at = #{now},
                   updated_by = #{studentId}, updated_at = #{now}, version = version + 1
             WHERE id = #{id} AND student_id = #{studentId} AND status = 'PENDING'
            """)
    int cancel(@Param("id") long id, @Param("studentId") long studentId,
               @Param("comment") String comment, @Param("now") LocalDateTime now);

    /**
     * 通过当前环节但流程未结束：级数 +1，状态仍为 PENDING。
     * 条件更新 {@code current_stage = #{stage}} 保证同一级只有一个审批人推进流程。
     */
    @Update("""
            UPDATE leave_request
               SET current_stage = #{nextStage}, reviewer_id = #{approverId}, review_comment = #{comment},
                   reviewed_at = #{now}, updated_by = #{approverId}, updated_at = #{now},
                   version = version + 1
             WHERE id = #{id} AND status = 'PENDING' AND current_stage = #{stage}
            """)
    int advanceStage(@Param("id") long id, @Param("stage") int stage, @Param("nextStage") int nextStage,
                     @Param("approverId") long approverId, @Param("comment") String comment,
                     @Param("now") LocalDateTime now);

    /** 最后一级给出结论：通过或驳回，写入终态。 */
    @Update("""
            UPDATE leave_request
               SET status = #{target}, reviewer_id = #{approverId}, review_comment = #{comment},
                   reviewed_at = #{now}, updated_by = #{approverId}, updated_at = #{now},
                   version = version + 1
             WHERE id = #{id} AND status = 'PENDING' AND current_stage = #{stage}
            """)
    int finishStage(@Param("id") long id, @Param("stage") int stage, @Param("target") LeaveStatus target,
                    @Param("approverId") long approverId, @Param("comment") String comment,
                    @Param("now") LocalDateTime now);

    /** 销假：申请人本人、已通过且尚未销假。 */
    @Update("""
            UPDATE leave_request
               SET revoked_at = #{now}, revoke_note = #{note}, revoked_by = #{studentId},
                   updated_by = #{studentId}, updated_at = #{now}, version = version + 1
             WHERE id = #{id} AND student_id = #{studentId} AND status = 'APPROVED' AND revoked_at IS NULL
            """)
    int revoke(@Param("id") long id, @Param("studentId") long studentId,
               @Param("note") String note, @Param("now") LocalDateTime now);

    /** 课程停开时撤销该课程全部待审批请假，避免留下无法处理的申请。 */
    @Update("""
            UPDATE leave_request
               SET status = 'CANCELLED', review_comment = #{comment}, reviewed_at = #{now},
                   updated_by = #{operatorId}, updated_at = #{now}, version = version + 1
             WHERE course_id = #{courseId} AND status = 'PENDING'
            """)
    int cancelPendingForCourse(@Param("courseId") long courseId, @Param("operatorId") Long operatorId,
                               @Param("comment") String comment, @Param("now") LocalDateTime now);
}
