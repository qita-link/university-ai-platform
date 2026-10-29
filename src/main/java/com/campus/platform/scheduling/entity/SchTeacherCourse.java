package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 教师可授课程。
 */
@Getter
@Setter
@TableName("sch_teacher_course")
public class SchTeacherCourse {

    private Long teacherId;
    private Long courseId;
    private LocalDateTime createdAt;
}
