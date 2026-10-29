package com.campus.platform.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.ai.entity.AiActHistory;
import org.apache.ibatis.annotations.Mapper;

/** 办事记录 Mapper；查询按 user_id + created_at 走索引，不做复杂条件。 */
@Mapper
public interface AiActHistoryMapper extends BaseMapper<AiActHistory> {
}
