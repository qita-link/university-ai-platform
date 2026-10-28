package com.campus.platform.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.campus.platform.common.persistence.AuditableEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户（sys_user）。账号不物理删除，通过 {@link #status} 停用。
 */
@Getter
@Setter
@TableName("sys_user")
public class SysUser extends AuditableEntity {

    private String username;
    /** 带算法前缀的密码哈希（如 {bcrypt}...），永不出现在接口响应和日志中 */
    private String passwordHash;
    private String displayName;
    private String email;
    private String phone;
    private Long orgUnitId;
    private UserStatus status;
    /** 递增后，所有已签发令牌立即失效 */
    private Integer tokenVersion;
    private Integer failedLoginCount;
    private LocalDateTime lockedUntil;
    private LocalDateTime lastLoginAt;
    private LocalDateTime passwordChangedAt;

    @Version
    private Integer version;

    @Override
    public String toString() {
        return "SysUser[id=" + getId() + ", username=" + username + ", status=" + status + "]";
    }
}
