package com.campus.platform.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.course.entity.CourseSchedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface CourseScheduleMapper extends BaseMapper<CourseSchedule> {

    @Select("""
            SELECT * FROM course_schedule
             WHERE course_id = #{courseId}
             ORDER BY day_of_week, start_period, id
            """)
    List<CourseSchedule> selectByCourse(@Param("courseId") long courseId);

    /** 批量取排课，用于课程列表/课表/冲突检测，避免 N+1。空集合返回空结果（不会拼出 IN ()）。 */
    @Select("""
            <script>
            SELECT * FROM course_schedule
             WHERE
            <choose>
              <when test="courseIds != null and courseIds.size() > 0">
                course_id IN
                <foreach collection="courseIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </when>
              <otherwise>1 = 0</otherwise>
            </choose>
             ORDER BY course_id, day_of_week, start_period, id
            </script>
            """)
    List<CourseSchedule> selectByCourses(@Param("courseIds") Collection<Long> courseIds);
}
