package com.campus.platform.forum.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.forum.entity.ForumComment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface ForumCommentMapper extends BaseMapper<ForumComment> {

    /** 某个帖子下所有顶级评论对应的回复（按 root、id 升序），用于组装两级线程。 */
    @Select("""
            <script>
            SELECT *
              FROM forum_comment
             WHERE post_id = #{postId}
               AND status = 'PUBLISHED'
               AND root_id IN
               <foreach collection="rootIds" item="rootId" open="(" separator="," close=")">#{rootId}</foreach>
             ORDER BY root_id ASC, id ASC
            </script>
            """)
    List<ForumComment> selectRepliesByRootIds(@Param("postId") long postId,
                                              @Param("rootIds") Collection<Long> rootIds);

    /**
     * 删除顶级评论及其全部回复（同一 root 线程）。
     * 返回实际从 PUBLISHED 改为 DELETED 的行数，用于扣减帖子评论计数。
     */
    @Update("""
            UPDATE forum_comment
               SET status = 'DELETED', deleted_at = #{now}, deleted_by = #{operatorId}, updated_at = #{now}
             WHERE post_id = #{postId}
               AND status = 'PUBLISHED'
               AND (id = #{commentId} OR root_id = #{commentId})
            """)
    int markThreadDeleted(@Param("postId") long postId, @Param("commentId") long commentId,
                          @Param("operatorId") Long operatorId, @Param("now") LocalDateTime now);

    /** 删除单条回复（其下的“回复的回复”仍保留在同一线程中）。 */
    @Update("""
            UPDATE forum_comment
               SET status = 'DELETED', deleted_at = #{now}, deleted_by = #{operatorId}, updated_at = #{now}
             WHERE id = #{commentId} AND status = 'PUBLISHED'
            """)
    int markDeleted(@Param("commentId") long commentId, @Param("operatorId") Long operatorId,
                    @Param("now") LocalDateTime now);
}
