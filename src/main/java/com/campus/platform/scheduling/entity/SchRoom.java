package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 教室与教学场地。
 */
@Getter
@Setter
@TableName("sch_room")
public class SchRoom {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;
    private String name;
    private Long campusId;
    private String building;
    private Integer floorNo;
    private Integer capacity;
    private String roomType;
    private String features;
    private String status;
    private String note;
    @Version
    private Integer version;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
