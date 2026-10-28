package com.campus.platform.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "角色及其权限点")
public record RoleView(Long id, String code, String name, String description, boolean builtIn,
                       List<String> permissions) {
}
