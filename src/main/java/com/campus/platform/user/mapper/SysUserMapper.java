package com.campus.platform.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.user.entity.SysUser;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 用户 Mapper。
 * <p>
 * <b>更新约定</b>：禁止用 {@code updateById(entity)} 更新用户。实体是先读后写的快照，
 * 会把读取时的 token_version / failed_login_count 等字段原样写回，覆盖期间发生的并发变更
 * （例如把已吊销令牌的 token_version 改回旧值，使旧令牌重新生效）。
 * 一律使用只修改目标列的 SQL：登录计数类更新不动 version，管理员修改资料时显式校验并递增 version。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /** 用户的角色编码 */
    @Select("""
            SELECT r.code
              FROM sys_role r
              JOIN sys_user_role ur ON ur.role_id = r.id
             WHERE ur.user_id = #{userId}
            """)
    List<String> selectRoleCodes(@Param("userId") long userId);

    /** 用户经由全部角色获得的权限点编码（去重） */
    @Select("""
            SELECT DISTINCT p.code
              FROM sys_permission p
              JOIN sys_role_permission rp ON rp.permission_id = p.id
              JOIN sys_user_role ur ON ur.role_id = rp.role_id
             WHERE ur.user_id = #{userId}
            """)
    List<String> selectPermissionCodes(@Param("userId") long userId);

    /** 拥有指定角色且状态为 ACTIVE 的用户数 */
    @Select("""
            SELECT COUNT(*)
              FROM sys_user u
              JOIN sys_user_role ur ON ur.user_id = u.id
              JOIN sys_role r ON r.id = ur.role_id
             WHERE r.code = #{roleCode} AND u.status = 'ACTIVE'
            """)
    long countActiveUsersWithRole(@Param("roleCode") String roleCode);

    /** 按角色编码为用户授予角色（已存在则忽略） */
    @Insert("""
            INSERT IGNORE INTO sys_user_role (user_id, role_id)
            SELECT #{userId}, r.id FROM sys_role r WHERE r.code = #{roleCode}
            """)
    int grantRole(@Param("userId") long userId, @Param("roleCode") String roleCode);

    // ---------------- 行锁 ----------------

    /** 锁定单个用户行（当前事务内），用于登录计数、密码修改等需要串行化的操作。 */
    @Select("SELECT * FROM sys_user WHERE id = #{id} FOR UPDATE")
    SysUser selectByIdForUpdate(@Param("id") long id);

    /**
     * 只锁定用户行不取数据，作为业务串行化闸门使用。
     * <p>
     * 选课/退课时先按学生加锁，把同一学生的“查冲突 → 写选课”串行化，
     * 避免并发选两门时间冲突的课程时双方都通过检查。
     */
    @Select("SELECT id FROM sys_user WHERE id = #{id} FOR UPDATE")
    Long lockById(@Param("id") long id);

    /**
     * 锁定全部 ACTIVE 管理员的用户行，按 id 顺序加锁避免死锁。
     * <p>
     * 停用用户、移除管理员角色之前调用：并发的两个请求（例如两个管理员互相停用）会在这里串行化，
     * 后执行的一方能看到前一方提交后的结果，从而保证“至少保留一个可用管理员”。
     */
    @Select("""
            SELECT u.id
              FROM sys_user u
              JOIN sys_user_role ur ON ur.user_id = u.id
              JOIN sys_role r ON r.id = ur.role_id
             WHERE r.code = 'ADMIN' AND u.status = 'ACTIVE'
             ORDER BY u.id
               FOR UPDATE
            """)
    List<Long> lockActiveAdminIds();

    // ---------------- 登录计数（不修改 version / updated_at，不与管理员编辑冲突） ----------------

    @Update("""
            UPDATE sys_user
               SET failed_login_count = #{failedCount}, locked_until = #{lockedUntil}, updated_at = updated_at
             WHERE id = #{id}
            """)
    int updateLoginFailure(@Param("id") long id, @Param("failedCount") int failedCount,
                           @Param("lockedUntil") LocalDateTime lockedUntil);

    @Update("""
            UPDATE sys_user
               SET failed_login_count = 0, locked_until = NULL, last_login_at = #{now}, updated_at = updated_at
             WHERE id = #{id}
            """)
    int updateLoginSuccess(@Param("id") long id, @Param("now") LocalDateTime now);

    /** 哈希算法/强度升级时透明重写哈希（登录成功后），不吊销令牌。 */
    @Update("UPDATE sys_user SET password_hash = #{newHash}, updated_at = updated_at WHERE id = #{id} AND password_hash = #{oldHash}")
    int rehashPassword(@Param("id") long id, @Param("oldHash") String oldHash, @Param("newHash") String newHash);

    // ---------------- 令牌与密码 ----------------

    /** token_version 自增：该用户已签发的全部令牌立即失效。 */
    @Update("UPDATE sys_user SET token_version = token_version + 1 WHERE id = #{id}")
    int incrementTokenVersion(@Param("id") long id);

    /**
     * 修改密码并吊销全部令牌、清除临时锁定。
     *
     * @param expectedHash 非空时作为条件（本人改密：防止与管理员重置并发时互相覆盖）
     */
    @Update("""
            <script>
            UPDATE sys_user
               SET password_hash = #{newHash}, password_changed_at = #{now},
                   token_version = token_version + 1, failed_login_count = 0, locked_until = NULL
                   <if test="operatorId != null">, updated_by = #{operatorId}</if>
             WHERE id = #{id}
               <if test="expectedHash != null">AND password_hash = #{expectedHash}</if>
            </script>
            """)
    int updatePassword(@Param("id") long id, @Param("newHash") String newHash, @Param("now") LocalDateTime now,
                       @Param("expectedHash") String expectedHash, @Param("operatorId") Long operatorId);

    // ---------------- 角色 ----------------

    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    int deleteRoles(@Param("userId") long userId);

    @Insert("""
            <script>
            INSERT INTO sys_user_role (user_id, role_id) VALUES
            <foreach collection="roleIds" item="rid" separator=",">(#{userId}, #{rid})</foreach>
            </script>
            """)
    int insertRoles(@Param("userId") long userId, @Param("roleIds") Collection<Long> roleIds);

    /** 一批用户各自的角色编码（列表页避免 N+1） */
    @Select("""
            <script>
            SELECT ur.user_id AS userId, r.code AS roleCode
              FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id
             WHERE ur.user_id IN
             <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<UserRoleCode> selectRoleCodesOfUsers(@Param("userIds") Collection<Long> userIds);

    /** 拥有某权限点的启用用户（用于把通知投给「能审批的人」，如教室借用审批） */
    @Select("""
            SELECT DISTINCT u.id
              FROM sys_user u
              JOIN sys_user_role ur ON ur.user_id = u.id
              JOIN sys_role_permission rp ON rp.role_id = ur.role_id
              JOIN sys_permission p ON p.id = rp.permission_id
             WHERE p.code = #{permission} AND u.status = 'ACTIVE'
            """)
    List<Long> selectActiveUserIdsByPermission(@Param("permission") String permission);

    /** 一批用户的头像版本（0 表示没有头像），用于列表里渲染头像 */
    @Select("""
            <script>
            SELECT id, avatar_version FROM sys_user WHERE id IN
            <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<AvatarVersionRow> selectAvatarVersions(@Param("userIds") Collection<Long> userIds);

    /** 用户 ID 与角色编码对 */
    record UserRoleCode(Long userId, String roleCode) {
    }

    /** 头像版本投影 */
    class AvatarVersionRow {
        private Long id;
        private Integer avatarVersion;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Integer getAvatarVersion() {
            return avatarVersion;
        }

        public void setAvatarVersion(Integer avatarVersion) {
            this.avatarVersion = avatarVersion;
        }
    }
}
