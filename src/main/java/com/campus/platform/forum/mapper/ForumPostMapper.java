package com.campus.platform.forum.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campus.platform.forum.entity.ForumPost;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface ForumPostMapper extends BaseMapper<ForumPost> {

    /** 锁定帖子行：评论创建/删除与帖子删除串行化，保证 comment_count 与最后活跃时间一致。 */
    @Select("SELECT * FROM forum_post WHERE id = #{id} FOR UPDATE")
    ForumPost selectByIdForUpdate(@Param("id") long id);

    /**
     * 帖子分页/关键词检索。
     * <p>
     * 关键词使用 {@code FULLTEXT(title, content)} + ngram；传入的 query 已经由
     * {@code SearchTerms} 拆成 2 字 ngram。没有关键词时按置顶、最后活跃时间排序。
     * 停用分类下的帖子不返回；已删除帖子由 status 条件排除。
     */
    @Select("""
            <script>
            SELECT p.*, c.name AS category_name
            <if test="query != null and query != ''">
                   , MATCH(p.title, p.content) AGAINST(#{query} IN BOOLEAN MODE) AS score
            </if>
              FROM forum_post p
              JOIN forum_category c ON c.id = p.category_id
             WHERE p.status = #{status}
               AND c.status = 'ACTIVE'
            <if test="categoryId != null">
               AND p.category_id = #{categoryId}
            </if>
            <if test="authorId != null">
               AND p.author_id = #{authorId}
            </if>
            <if test="query != null and query != ''">
               AND MATCH(p.title, p.content) AGAINST(#{query} IN BOOLEAN MODE) > 0
            </if>
             ORDER BY p.pinned DESC,
            <if test="query != null and query != ''">
                      score DESC,
            </if>
                      p.last_activity_at DESC, p.id DESC
            </script>
            """)
    IPage<ForumPostRow> selectPostPage(IPage<ForumPostRow> page,
                                       @Param("query") String query,
                                       @Param("categoryId") Long categoryId,
                                       @Param("authorId") Long authorId,
                                       @Param("status") String status);

    /** 新增评论后计数 +1 并刷新最后活跃时间；不递增 version（version 只跟踪内容修改）。 */
    @Update("""
            UPDATE forum_post
               SET comment_count = comment_count + 1, last_activity_at = #{now}, updated_at = #{now}
             WHERE id = #{id} AND status = 'PUBLISHED'
            """)
    int incrementCommentCount(@Param("id") long id, @Param("now") LocalDateTime now);

    /** 删除评论后按实际删除条数扣减，使用 GREATEST 防止计数变成负数。 */
    @Update("""
            UPDATE forum_post
               SET comment_count = GREATEST(comment_count - #{count}, 0), updated_at = #{now}
             WHERE id = #{id}
            """)
    int decrementCommentCount(@Param("id") long id, @Param("count") int count,
                              @Param("now") LocalDateTime now);

    /** 各分类下已发布帖子数，用于分类列表；ids 为空时由调用方跳过。 */
    @Select("""
            <script>
            SELECT category_id AS categoryId, COUNT(*) AS total
              FROM forum_post
             WHERE status = 'PUBLISHED'
               AND category_id IN
               <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
             GROUP BY category_id
            </script>
            """)
    List<CategoryPostCount> countPublishedByCategories(@Param("ids") Collection<Long> ids);
}
