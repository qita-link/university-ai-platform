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

    /**
     * 审批（通过/驳回）：{@code WHERE status = 'PENDING'} 条件更新，
     * 并发审批只有一个请求能写入结果，其余返回 0 行由调用方转为 409。
     */
    @Update("""
            UPDATE leave_request
               SET status = #{target}, reviewer_id = #{reviewerId}, review_comment = #{comment},
                   reviewed_at = #{now}, updated_by = #{reviewerId}, updated_at = #{now},
                   version = version + 1
             WHERE id = #{id} AND status = 'PENDING'
            """)
    int decide(@Param("id") long id, @Param("target") LeaveStatus target, @Param("reviewerId") long reviewerId,
               @Param("comment") String comment, @Param("now") LocalDateTime now);

    /** 申请人撤销：只能撤销本人且仍为 PENDING 的申请。 */
    @Update("""
            UPDATE leave_request
               SET status = 'CANCELLED', review_comment = #{comment}, reviewed_at = #{now},
                   updated_by = #{studentId}, updated_at = #{now}, version = version + 1
             WHERE id = #{id} AND student_id = #{studentId} AND status = 'PENDING'
            """)
    int cancel(@Param("id") long id, @Param("studentId") long studentId,
               @Param("comment") String comment, @Param("now") LocalDateTime now);

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
