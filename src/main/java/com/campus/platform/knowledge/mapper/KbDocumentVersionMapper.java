package com.campus.platform.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.knowledge.entity.KbDocumentVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KbDocumentVersionMapper extends BaseMapper<KbDocumentVersion> {

    /** 在文档行锁内调用，返回下一个版本号。 */
    @Select("SELECT COALESCE(MAX(version_no), 0) + 1 FROM kb_document_version WHERE document_id = #{documentId}")
    int nextVersionNo(@Param("documentId") long documentId);
}
