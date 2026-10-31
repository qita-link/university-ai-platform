package com.campus.platform.grade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.grade.entity.CourseGrade;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CourseGradeMapper extends BaseMapper<CourseGrade> {

    @Select("""
            SELECT * FROM course_grade WHERE course_id = #{courseId} AND student_id = #{studentId}
            """)
    CourseGrade selectOneByCourseAndStudent(@Param("courseId") long courseId, @Param("studentId") long studentId);

    @Select("""
            SELECT * FROM course_grade
             WHERE course_id = #{courseId} ORDER BY student_id
            """)
    List<CourseGrade> selectByCourse(@Param("courseId") long courseId);

    @Select("""
            SELECT * FROM course_grade
             WHERE student_id = #{studentId} AND status = 'PUBLISHED'
             ORDER BY published_at DESC, id DESC
            """)
    List<CourseGrade> selectPublishedByStudent(@Param("studentId") long studentId);

    /** 发布整门课程：只有已录入分数的记录会被发布。 */
    @Update("""
            UPDATE course_grade
               SET status = 'PUBLISHED', published_at = #{now},
                   updated_by = #{operatorId}, updated_at = #{now}, version = version + 1
             WHERE course_id = #{courseId} AND status = 'DRAFT' AND score IS NOT NULL
            """)
    int publishCourse(@Param("courseId") long courseId, @Param("operatorId") Long operatorId,
                      @Param("now") LocalDateTime now);
}
