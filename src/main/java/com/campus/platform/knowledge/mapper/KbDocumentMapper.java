package com.campus.platform.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.knowledge.entity.KbDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {

    /** 编辑/重处理前锁定文档行，串行化版本号生成与 current_version_id 更新。 */
    @Select("SELECT * FROM kb_document WHERE id = #{id} FOR UPDATE")
    KbDocument selectByIdForUpdate(@Param("id") long id);
}
