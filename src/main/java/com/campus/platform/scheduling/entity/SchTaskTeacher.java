package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 教学任务授课教师（共同授课全部计入工作量与冲突）。
 */
@Getter
@Setter
@TableName("sch_task_teacher")
public class SchTaskTeacher {

    private Long taskId;
    private Long teacherId;
    private String teacherRole;
    private LocalDateTime createdAt;
}
