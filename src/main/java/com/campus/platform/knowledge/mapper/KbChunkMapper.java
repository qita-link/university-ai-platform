package com.campus.platform.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.knowledge.entity.KbChunk;
import com.campus.platform.retrieval.mapper.ChunkSearchRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /** 文档内容变更/删除时，把旧块标记为 STALE，保证检索不再命中旧内容。 */
    @Update("UPDATE kb_chunk SET status = 'STALE' WHERE document_id = #{documentId} AND status = 'ACTIVE'")
    int markStaleByDocument(@Param("documentId") long documentId);

    @Select("SELECT COUNT(*) FROM kb_chunk WHERE document_id = #{documentId} AND status = 'ACTIVE'")
    long countActiveByDocument(@Param("documentId") long documentId);

    /**
     * MySQL ngram 关键词检索。标题单独一个 FULLTEXT 索引并乘以 4，正文/章节使用另一个索引。
     * 权限过滤在 SQL 之前完成，这里只接受调用方已确认可读的 spaceIds。
     */
    @Select("""
            <script>
            SELECT c.id, c.document_id, c.version_id, c.space_id, c.document_title,
                   c.heading_path, c.content, c.char_count, c.token_estimate,
                   (MATCH(c.document_title) AGAINST(#{query} IN BOOLEAN MODE) * 4
                    + MATCH(c.heading_path, c.content) AGAINST(#{query} IN BOOLEAN MODE)) AS score
              FROM kb_chunk c
             WHERE c.status = 'ACTIVE'
               AND c.space_id IN
               <foreach collection="spaceIds" item="sid" open="(" separator="," close=")">#{sid}</foreach>
               AND (MATCH(c.document_title) AGAINST(#{query} IN BOOLEAN MODE) > 0
                    OR MATCH(c.heading_path, c.content) AGAINST(#{query} IN BOOLEAN MODE) > 0)
             ORDER BY score DESC, c.id DESC
             LIMIT #{limit}
            </script>
            """)
    List<ChunkSearchRow> search(@Param("query") String query, @Param("spaceIds") List<Long> spaceIds,
                                @Param("limit") int limit);
}
