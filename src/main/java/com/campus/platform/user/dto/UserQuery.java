package com.campus.platform.user.dto;

import com.campus.platform.common.api.PageQuery;
import com.campus.platform.user.entity.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "用户查询条件")
public class UserQuery extends PageQuery {

    @Schema(description = "按用户名或显示名模糊匹配")
    @Size(max = 64, message = "关键字最长 64 字")
    private String keyword;

    @Schema(description = "账号状态")
    private UserStatus status;

    @Schema(description = "组织 ID")
    @Positive
    private Long orgUnitId;

    @Schema(description = "为 true 时同时包含 orgUnitId 的全部下级组织", defaultValue = "false")
    private boolean includeSubOrgs;

    @Schema(description = "角色编码")
    @Pattern(regexp = UserValidation.ROLE_CODE_REGEX, message = "角色编码格式错误")
    private String roleCode;
}
