package com.campus.platform.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.course.entity.CourseSelection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CourseSelectionMapper extends BaseMapper<CourseSelection> {

    /** 学生在该课程上的有效选课记录（可能为空）。 */
    @Select("""
            SELECT * FROM course_selection
             WHERE course_id = #{courseId} AND student_id = #{studentId} AND status = 'SELECTED'
            """)
    CourseSelection selectActive(@Param("courseId") long courseId, @Param("studentId") long studentId);

    @Select("""
            SELECT course_id FROM course_selection
             WHERE student_id = #{studentId} AND status = 'SELECTED'
            """)
    List<Long> selectActiveCourseIds(@Param("studentId") long studentId);

    @Select("SELECT COUNT(*) FROM course_selection WHERE course_id = #{courseId} AND status = 'SELECTED'")
    long countActiveByCourse(@Param("courseId") long courseId);

    /**
     * 退课：条件更新保证只有本人、且仍为 SELECTED 的记录能被退掉，
     * 并发退课/停开课程时只有一个请求成功，不会重复扣减容量。
     */
    @Update("""
            UPDATE course_selection
               SET status = 'DROPPED', dropped_at = #{now}, updated_by = #{operatorId}, updated_at = #{now}
             WHERE id = #{id} AND student_id = #{studentId} AND status = 'SELECTED'
            """)
    int markDropped(@Param("id") long id, @Param("studentId") long studentId,
                    @Param("operatorId") Long operatorId, @Param("now") LocalDateTime now);

    /** 停开课程时批量退掉全部有效选课。 */
    @Update("""
            UPDATE course_selection
               SET status = 'DROPPED', dropped_at = #{now}, updated_by = #{operatorId}, updated_at = #{now}
             WHERE course_id = #{courseId} AND status = 'SELECTED'
            """)
    int dropAllForCourse(@Param("courseId") long courseId, @Param("operatorId") Long operatorId,
                         @Param("now") LocalDateTime now);
}
