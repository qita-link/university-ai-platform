package com.campus.platform.course.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 班级导员（class_counselor）：复合主键，不继承 BaseMapper。
 */
@Mapper
public interface ClassCounselorMapper {

    @Select("SELECT counselor_id FROM class_counselor WHERE class_id = #{classId} ORDER BY counselor_id")
    List<Long> selectCounselorIds(@Param("classId") long classId);

    @Select("SELECT class_id FROM class_counselor WHERE counselor_id = #{counselorId} ORDER BY class_id")
    List<Long> selectClassIds(@Param("counselorId") long counselorId);

    @Select("""
            <script>
            SELECT DISTINCT class_id FROM class_counselor WHERE counselor_id = #{counselorId} AND class_id IN
            <foreach collection="classIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> selectCounseledClassIds(@Param("counselorId") long counselorId,
                                       @Param("classIds") Collection<Long> classIds);

    @Select("SELECT COUNT(*) FROM class_counselor WHERE class_id = #{classId} AND counselor_id = #{counselorId}")
    int count(@Param("classId") long classId, @Param("counselorId") long counselorId);

    @Insert("""
            INSERT INTO class_counselor (class_id, counselor_id, created_by)
            VALUES (#{classId}, #{counselorId}, #{operatorId})
            """)
    int insert(@Param("classId") long classId, @Param("counselorId") long counselorId,
               @Param("operatorId") Long operatorId);

    @Delete("DELETE FROM class_counselor WHERE class_id = #{classId}")
    int deleteByClass(@Param("classId") long classId);
}
