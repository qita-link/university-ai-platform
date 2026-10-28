package com.campus.platform.common.persistence;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.campus.platform.security.SecurityUtils;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 自动填充 created_at / updated_at / created_by / updated_by。
 * strictXxxFill 只会填充实体中声明了对应填充注解的字段，因此对所有实体通用。
 */
@Component
public class AuditFieldsMetaObjectHandler implements MetaObjectHandler {

    private final Clock clock;

    public AuditFieldsMetaObjectHandler(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now(clock);
        Long userId = SecurityUtils.currentUserIdOrNull();
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "createdBy", Long.class, userId);
        strictInsertFill(metaObject, "updatedBy", Long.class, userId);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // 更新时间总是覆盖为当前时间（setFieldValByName），而不是仅在为空时填充
        if (metaObject.hasSetter("updatedAt")) {
            setFieldValByName("updatedAt", LocalDateTime.now(clock), metaObject);
        }
        Long userId = SecurityUtils.currentUserIdOrNull();
        if (userId != null && metaObject.hasSetter("updatedBy")) {
            setFieldValByName("updatedBy", userId, metaObject);
        }
    }
}
