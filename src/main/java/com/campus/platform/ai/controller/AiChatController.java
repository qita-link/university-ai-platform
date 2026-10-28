package com.campus.platform.ai.controller;

import com.campus.platform.ai.dto.AiChatRequest;
import com.campus.platform.ai.dto.AiChatResponse;
import com.campus.platform.ai.dto.ConversationDetailView;
import com.campus.platform.ai.dto.ConversationView;
import com.campus.platform.ai.service.AiChatService;
import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.common.api.PageQuery;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AI 知识库问答", description = "基于有权访问的知识库片段回答，返回引用与 Token 用量")
@RestController
@RequestMapping("/api/v1/ai")
public class AiChatController {

    private final AiChatService chatService;

    public AiChatController(AiChatService chatService) {
        this.chatService = chatService;
    }

    @Operation(summary = "知识库问答", description = "需要 ai:chat；检索结果不足时返回 insufficientEvidence，不调用模型")
    @PreAuthorize("hasAuthority('ai:chat')")
    @PostMapping("/chat")
    public ApiResponse<AiChatResponse> chat(@AuthenticationPrincipal AuthenticatedUser user,
                                            @Valid @RequestBody AiChatRequest request) {
        return ApiResponse.ok(chatService.chat(user, request));
    }

    @Operation(summary = "本人会话列表")
    @PreAuthorize("hasAuthority('ai:chat')")
    @GetMapping("/conversations")
    public ApiResponse<PageResult<ConversationView>> conversations(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @ParameterObject PageQuery page) {
        return ApiResponse.ok(chatService.listConversations(user, page));
    }

    @Operation(summary = "会话详情（消息与引用）")
    @PreAuthorize("hasAuthority('ai:chat')")
    @GetMapping("/conversations/{id}")
    public ApiResponse<ConversationDetailView> conversation(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return ApiResponse.ok(chatService.conversation(user, id));
    }

    @Operation(summary = "删除会话", description = "软删除；只能删除本人会话")
    @PreAuthorize("hasAuthority('ai:chat')")
    @DeleteMapping("/conversations/{id}")
    public ApiResponse<Void> deleteConversation(@AuthenticationPrincipal AuthenticatedUser user,
                                                @PathVariable long id) {
        chatService.deleteConversation(user, id);
        return ApiResponse.ok();
    }
}
