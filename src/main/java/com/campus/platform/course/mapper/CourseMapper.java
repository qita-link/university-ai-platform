package com.campus.platform.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.course.entity.Course;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface CourseMapper extends BaseMapper<Course> {

    /** 锁定课程行：停开课程、缩容等需要与选课/退课串行化的操作用。 */
    @Select("SELECT * FROM course WHERE id = #{id} FOR UPDATE")
    Course selectByIdForUpdate(@Param("id") long id);

    /**
     * 选课名额占用：单条条件更新完成“判断容量 + 计数 +1”，避免先查后改导致超卖。
     * <p>
     * 返回 1 表示占用成功；返回 0 表示课程已不开放或已满，由调用方区分原因并提示。
     */
    @Update("""
            UPDATE course
               SET selected_count = selected_count + 1, updated_at = #{now}
             WHERE id = #{id}
               AND status = 'OPEN'
               AND selected_count < capacity
            """)
    int incrementSelectedCount(@Param("id") long id, @Param("now") LocalDateTime now);

    /** 退课释放名额，GREATEST 防止计数变成负数。 */
    @Update("""
            UPDATE course
               SET selected_count = GREATEST(selected_count - 1, 0), updated_at = #{now}
             WHERE id = #{id}
            """)
    int decrementSelectedCount(@Param("id") long id, @Param("now") LocalDateTime now);

    /** 停开课程时清零计数（配合批量退课）。 */
    @Update("UPDATE course SET selected_count = 0, updated_at = #{now} WHERE id = #{id}")
    int resetSelectedCount(@Param("id") long id, @Param("now") LocalDateTime now);
}
