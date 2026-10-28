package com.campus.platform.course.mapper;

import com.campus.platform.course.entity.CourseTeacher;
import com.campus.platform.course.entity.TeacherRole;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * 课程授课教师 Mapper。
 * <p>
 * 表使用复合主键 (course_id, teacher_id)，没有自增主键，因此不继承 BaseMapper，
 * 全部通过显式 SQL 读写。
 */
@Mapper
public interface CourseTeacherMapper {

    @Insert("""
            INSERT INTO course_teacher (course_id, teacher_id, teacher_role, created_by, updated_by)
            VALUES (#{courseId}, #{teacherId}, #{role}, #{operatorId}, #{operatorId})
            """)
    int insert(@Param("courseId") long courseId, @Param("teacherId") long teacherId,
               @Param("role") TeacherRole role, @Param("operatorId") Long operatorId);

    @Delete("DELETE FROM course_teacher WHERE course_id = #{courseId} AND teacher_id = #{teacherId}")
    int delete(@Param("courseId") long courseId, @Param("teacherId") long teacherId);

    @Select("""
            SELECT * FROM course_teacher
             WHERE course_id = #{courseId}
             ORDER BY teacher_id
            """)
    List<CourseTeacher> selectByCourse(@Param("courseId") long courseId);

    @Select("""
            <script>
            SELECT * FROM course_teacher
             WHERE
            <choose>
              <when test="courseIds != null and courseIds.size() > 0">
                course_id IN
                <foreach collection="courseIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </when>
              <otherwise>1 = 0</otherwise>
            </choose>
             ORDER BY course_id, teacher_id
            </script>
            """)
    List<CourseTeacher> selectByCourses(@Param("courseIds") Collection<Long> courseIds);

    @Select("""
            SELECT * FROM course_teacher
             WHERE course_id = #{courseId} AND teacher_id = #{teacherId}
            """)
    CourseTeacher selectOne(@Param("courseId") long courseId, @Param("teacherId") long teacherId);

    @Update("""
            UPDATE course_teacher
               SET teacher_role = #{role}, updated_by = #{operatorId}, updated_at = CURRENT_TIMESTAMP(3)
             WHERE course_id = #{courseId} AND teacher_id = #{teacherId}
            """)
    int updateRole(@Param("courseId") long courseId, @Param("teacherId") long teacherId,
                   @Param("role") TeacherRole role, @Param("operatorId") Long operatorId);

    @Select("SELECT COUNT(*) FROM course_teacher WHERE course_id = #{courseId} AND teacher_role = #{role}")
    long countByRole(@Param("courseId") long courseId, @Param("role") TeacherRole role);

    /** 这些教师任教（主讲或助教）的课程 ID，用于排课冲突与“我的课表”。 */
    @Select("""
            <script>
            SELECT DISTINCT course_id FROM course_teacher
             WHERE
            <choose>
              <when test="teacherIds != null and teacherIds.size() > 0">
                teacher_id IN
                <foreach collection="teacherIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </when>
              <otherwise>1 = 0</otherwise>
            </choose>
            </script>
            """)
    List<Long> selectCourseIdsByTeachers(@Param("teacherIds") Collection<Long> teacherIds);
}
