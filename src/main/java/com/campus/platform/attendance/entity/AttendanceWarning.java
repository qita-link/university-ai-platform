package com.campus.platform.attendance.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 缺勤预警（attendance_warning）：同一学生同一课程在同一累计次数上只提醒一次。
 */
@Getter
@Setter
@TableName("attendance_warning")
public class AttendanceWarning {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long studentId;
    private Long courseId;
    private Integer absentCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
