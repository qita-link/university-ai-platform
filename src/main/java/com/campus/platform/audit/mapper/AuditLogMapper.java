package com.campus.platform.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.audit.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
