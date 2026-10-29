package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 校区间通行时间，按无序对存储（from < to）。
 */
@Getter
@Setter
@TableName("sch_campus_travel")
public class SchCampusTravel {

    private Long fromCampusId;
    private Long toCampusId;
    private Integer minutes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
