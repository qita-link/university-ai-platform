package com.campus.platform.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.notification.entity.Notification;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    @Update("""
            UPDATE notification
               SET read_at = #{now}
             WHERE id = #{id} AND user_id = #{userId} AND read_at IS NULL
            """)
    int markRead(@Param("id") long id, @Param("userId") long userId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE notification
               SET read_at = #{now}
             WHERE user_id = #{userId} AND read_at IS NULL
            """)
    int markAllRead(@Param("userId") long userId, @Param("now") LocalDateTime now);

    /**
     * 广播：一条 INSERT ... SELECT 给全部启用用户写站内信，避免逐用户开事务。
     * <p>
     * {@code roleCode} 为空表示全校，否则只发给该角色的用户；
     * {@code excludeUserId} 用于把发布者本人排除，避免自己收到自己的公告。
     */
    @Insert("""
            <script>
            INSERT INTO notification (user_id, category, level, title, body, link_url, biz_type, biz_id,
                                      popup_required, created_at)
            SELECT u.id, #{category}, #{level}, #{title}, #{body}, #{linkUrl}, #{bizType}, #{bizId},
                   #{popupRequired}, #{now}
              FROM sys_user u
             WHERE u.status = 'ACTIVE'
               AND (#{excludeUserId} IS NULL OR u.id != #{excludeUserId})
               <if test="roleCode != null">
               AND EXISTS (
                     SELECT 1 FROM sys_user_role ur
                       JOIN sys_role r ON r.id = ur.role_id
                      WHERE ur.user_id = u.id AND r.code = #{roleCode})
               </if>
            </script>
            """)
    int broadcast(@Param("category") String category, @Param("level") String level,
                  @Param("title") String title, @Param("body") String body,
                  @Param("linkUrl") String linkUrl, @Param("bizType") String bizType,
                  @Param("bizId") Long bizId, @Param("popupRequired") boolean popupRequired,
                  @Param("roleCode") String roleCode, @Param("excludeUserId") Long excludeUserId,
                  @Param("now") LocalDateTime now);
}
