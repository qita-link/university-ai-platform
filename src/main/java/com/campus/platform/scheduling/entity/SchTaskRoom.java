package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 教学任务教室要求：REQUIRED/PREFERRED/FORBIDDEN。
 */
@Getter
@Setter
@TableName("sch_task_room")
public class SchTaskRoom {

    private Long taskId;
    private Long roomId;
    private String mode;
    private LocalDateTime createdAt;
}
