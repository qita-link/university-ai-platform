package com.campus.platform.profile.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.common.api.PageQuery;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.profile.dto.ProfilePostView;
import com.campus.platform.profile.dto.ProfileView;
import com.campus.platform.profile.service.ProfileService;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

@Tag(name = "个人资料", description = "本人信息、头像与我发过的帖子")
@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @Operation(summary = "我的资料", description = "身份、学号/工号、姓名、组织与角色")
    @GetMapping
    public ApiResponse<ProfileView> me(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(profileService.profile(user));
    }

    @Operation(summary = "上传头像", description = "PNG/JPEG/WebP，≤1.5MB；成功后头像版本自增")
    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProfileView> uploadAvatar(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(profileService.uploadAvatar(user, file));
    }

    @Operation(summary = "删除头像", description = "回到姓名首字头像")
    @DeleteMapping("/avatar")
    public ApiResponse<ProfileView> removeAvatar(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(profileService.removeAvatar(user));
    }

    /**
     * 头像图片：需要登录（浏览器 <img> 带不上 Bearer，所以前端用 fetch 取 blob）。
     * URL 里带 {@code ?v=avatarVersion}，换头像后自然命中新地址。
     */
    @Operation(summary = "读取头像", description = "登录用户可读；没有头像返回 404")
    @GetMapping("/avatar/{userId}")
    public ResponseEntity<byte[]> avatar(@PathVariable long userId) {
        ProfileService.AvatarFile file = profileService.avatar(userId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .body(file.bytes());
    }

    @Operation(summary = "我发过的帖子", description = "含被版主隐藏的帖子，不含已删除")
    @GetMapping("/posts")
    public ApiResponse<PageResult<ProfilePostView>> myPosts(@AuthenticationPrincipal AuthenticatedUser user,
                                                           @Valid @ParameterObject PageQuery query) {
        return ApiResponse.ok(profileService.myPosts(user, query));
    }
}
