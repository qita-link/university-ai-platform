package com.campus.platform.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.ai.config.AiProperties;
import com.campus.platform.ai.dto.ActHistoryView;
import com.campus.platform.ai.dto.AgentActRequest;
import com.campus.platform.ai.dto.AgentActResponse;
import com.campus.platform.ai.dto.AgentStep;
import com.campus.platform.ai.dto.AiToolCallRequest;
import com.campus.platform.ai.dto.AiToolCallView;
import com.campus.platform.ai.entity.AiActHistory;
import com.campus.platform.ai.mapper.AiActHistoryMapper;
import com.campus.platform.ai.tool.AiTool;
import com.campus.platform.ai.tool.AiToolRegistry;
import com.campus.platform.ai.tool.ToolParameter;
import com.campus.platform.common.api.PageQuery;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.course.dto.SemesterView;
import com.campus.platform.course.service.SemesterService;
import com.campus.platform.forum.dto.ForumCategoryView;
import com.campus.platform.forum.service.ForumCategoryService;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 自然语言办事（把“一句话”翻译成工具调用）。
 * <p>
 * 与 {@link AiChatService} 的分工：问答走知识库检索，只回答“是什么”；
 * 办事走工具调用，回答“帮我做”。两者都不允许模型直接改数据：
 * <ul>
 *   <li>只读工具：本服务代模型执行，结果回灌给模型，模型据此继续或作答；</li>
 *   <li>写工具：<b>不执行</b>，只经 {@link AiToolService#invoke} 生成待确认记录，
 *       由用户在界面上点“确认执行”后才真正落库（复用既有确认闸门，工具内不再有第二条执行路径）。</li>
 * </ul>
 * 模型不直接持有 {@link AuthenticatedUser}，每次执行都显式传入；权限与数据归属由被调的 Service 判断。
 * <p>
 * 采用“单步 JSON 协议”而非原生 function calling：模型每次只回一个 JSON 对象
 * （call / ask / answer），平台校验参数后执行，再把结果作为观察回灌进行下一轮。
 * 这样即使模型或网关不支持 tools 字段也能用，且每一步都能落审计、可在界面上逐条展示。
 */
@Slf4j
@Service
public class AgentService {

    /** 最多让模型“看一看再决定”的次数，防止死循环烧 token。 */
    private static final int MAX_ROUNDS = 4;
    /** 同一轮内连续失败的上限；超过就交还给用户。 */
    private static final int MAX_FAILURES = 3;
    /** 回灌给模型的工具结果长度上限。 */
    private static final int MAX_OBSERVATION = 4000;

    private final AiToolRegistry registry;
    private final AiToolService toolService;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final AiProperties aiProperties;
    private final SemesterService semesterService;
    private final ForumCategoryService forumCategoryService;
    private final AiActHistoryMapper historyMapper;
    private final com.campus.platform.ai.mapper.AiToolCallMapper toolCallMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String configuredModel;

    public AgentService(AiToolRegistry registry, AiToolService toolService,
                        ObjectProvider<ChatModel> chatModelProvider, AiProperties aiProperties,
                        SemesterService semesterService, ForumCategoryService forumCategoryService,
                        AiActHistoryMapper historyMapper,
                        com.campus.platform.ai.mapper.AiToolCallMapper toolCallMapper,
                        ObjectMapper objectMapper, Clock clock,
                        @Value("${spring.ai.openai.chat.options.model:unknown}") String configuredModel) {
        this.registry = registry;
        this.toolService = toolService;
        this.chatModelProvider = chatModelProvider;
        this.aiProperties = aiProperties;
        this.semesterService = semesterService;
        this.forumCategoryService = forumCategoryService;
        this.historyMapper = historyMapper;
        this.toolCallMapper = toolCallMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.configuredModel = configuredModel;
    }

    public AgentActResponse act(AuthenticatedUser user, AgentActRequest request) {
        Permissions.require(user, Permissions.AI_TOOL_USE);
        String message = request.message() == null ? "" : request.message().trim();
        if (message.isEmpty()) {
            throw BusinessException.badRequest("请说明要办的事");
        }
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "AI 未启用，请联系管理员配置模型");
        }

        List<AiTool> tools = registry.all().stream().filter(t -> canUse(user, t)).toList();
        if (tools.isEmpty()) {
            throw BusinessException.forbidden("当前账号没有可用的办事工具");
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt(user, tools)));
        messages.add(new UserMessage(message));

        List<AgentStep> steps = new ArrayList<>();
        int round = 0;
        int failures = 0;
        while (round < MAX_ROUNDS) {
            round++;
            String raw = complete(model, messages);
            Plan plan = parsePlan(raw);
            if (plan == null) {
                // 模型没按协议输出：把它的话原样交给用户，不做任何执行
                return respond(user, message, steps,
                        new AgentActResponse(raw == null ? "" : raw.trim(), steps, null, "RAW_TEXT", configuredModel));
            }
            if (!"call".equals(plan.action())) {
                String reply = blankToNull(plan.message()) == null ? raw.trim() : plan.message();
                return respond(user, message, steps,
                        new AgentActResponse(reply, steps, null, "DONE", configuredModel));
            }

            AiTool tool = registry.find(plan.tool()).orElse(null);
            if (tool == null || !canUse(user, tool)) {
                String error = "工具 " + plan.tool() + " 不存在或当前账号无权使用";
                steps.add(new AgentStep(round, nullToEmpty(plan.tool()), "未找到工具", false,
                        plan.argumentsNode(), "FAILED", null, ErrorCode.AI_TOOL_ARGUMENT_INVALID.name(), error, null));
                messages.add(new AssistantMessage(raw));
                messages.add(new UserMessage("【平台】" + error + "。请改用可用工具，或用 ask 向用户说明。"));
                if (++failures >= MAX_FAILURES) {
                    return respond(user, message, steps, new AgentActResponse(
                        "没能找到合适的工具来完成这件事：" + error, steps, null, "MAX_ROUNDS", configuredModel));
                }
                continue;
            }

            AiToolCallView call;
            try {
                call = toolService.invoke(user, new AiToolCallRequest(tool.name(), plan.arguments(), null));
            } catch (BusinessException ex) {
                steps.add(new AgentStep(round, tool.name(), tool.title(), tool.sideEffect(),
                        plan.argumentsNode(), "FAILED", null, ex.getErrorCode().name(), ex.getMessage(), null));
                messages.add(new AssistantMessage(raw));
                messages.add(new UserMessage("【平台】工具 " + tool.name() + " 执行失败：" + ex.getMessage()
                        + "。请修正参数后重试；若缺少信息，用 ask 向用户询问。"));
                if (++failures >= MAX_FAILURES) {
                    return respond(user, message, steps, new AgentActResponse(
                        "这一步没能办成：" + ex.getMessage(), steps, null, "MAX_ROUNDS", configuredModel));
                }
                continue;
            }

            steps.add(new AgentStep(round, tool.name(), tool.title(), tool.sideEffect(),
                    call.arguments(), call.status(), call.resultSummary(), call.errorCode(), call.errorMessage(), call.id()));

            if (tool.sideEffect()) {
                // 写操作：到此为止，等用户确认；绝不代表已经完成
                String reply = blankToNull(plan.message()) != null ? plan.message()
                        : "已生成待确认的操作，请核对下方内容后确认执行。";
                return respond(user, message, steps,
                        new AgentActResponse(reply, steps, call, "WAITING_CONFIRM", configuredModel));
            }

            messages.add(new AssistantMessage(raw));
            messages.add(new UserMessage("【平台】工具 " + tool.name() + " 的结果（" + call.status() + "）：\n"
                    + observation(call) + "\n请据此继续：还需要别的工具就继续 call，否则用 answer 回答用户。"));
        }

        return respond(user, message, steps, new AgentActResponse(
                "这件事需要多步确认，已经停下。请把要办的事说得更具体一些，或分步提出。",
                steps, null, "MAX_ROUNDS", configuredModel));
    }

    // ------------------------------------------------------------------ 历史

    /**
     * 办事记录分页：本人记录，管理员可查看全部（可按 userId 过滤）。
     * <p>
     * 只做展示，不回放执行：历史里的步骤是当时的快照，点开只能看，不能再点一次“确认”。
     */
    public PageResult<ActHistoryView> listHistory(AuthenticatedUser user, PageQuery page, Long userId) {
        Permissions.require(user, Permissions.AI_TOOL_USE);
        LambdaQueryWrapper<AiActHistory> w = new LambdaQueryWrapper<>();
        if (user.isAdmin()) {
            w.eq(userId != null, AiActHistory::getUserId, userId);
        } else {
            w.eq(AiActHistory::getUserId, user.userId());
        }
        w.orderByDesc(AiActHistory::getId);
        var result = historyMapper.selectPage(page.toPage(), w);
        // 确认单状态要实时：一次办事当时只生成待确认，之后可能被确认/放弃/过期
        List<Long> pendingIds = result.getRecords().stream()
                .map(AiActHistory::getPendingId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> pendingStatus = new java.util.HashMap<>();
        if (!pendingIds.isEmpty()) {
            toolCallMapper.selectBatchIds(pendingIds).forEach(call -> pendingStatus.put(call.getId(),
                    call.getStatus() == null ? null : call.getStatus().name()));
        }
        return PageResult.of(result,
                history -> historyView(history, pendingStatus.get(history.getPendingId())));
    }

    /** 落一条办事记录后返回原响应；写历史失败只记日志，不能让一次已完成的办事变成报错。 */
    private AgentActResponse respond(AuthenticatedUser user, String message, List<AgentStep> steps,
                                     AgentActResponse response) {
        try {
            AiActHistory history = new AiActHistory();
            history.setUserId(user.userId());
            history.setMessage(message.length() > 1000 ? message.substring(0, 1000) : message);
            history.setReply(response.reply());
            history.setOutcome(response.outcome());
            history.setStepCount(steps.size());
            history.setStepsJson(steps.isEmpty() ? null : objectMapper.writeValueAsString(steps));
            history.setPendingId(response.pending() == null ? null : response.pending().id());
            history.setModel(response.model());
            historyMapper.insert(history);
        } catch (Exception ex) {
            log.warn("办事记录写入失败，本次结果不受影响", ex);
        }
        return response;
    }

    private ActHistoryView historyView(AiActHistory history, String pendingStatus) {
        List<AgentStep> steps = List.of();
        if (history.getStepsJson() != null && !history.getStepsJson().isBlank()) {
            try {
                steps = objectMapper.readValue(history.getStepsJson(),
                        new TypeReference<List<AgentStep>>() {
                        });
            } catch (Exception ex) {
                log.debug("办事记录的步骤快照无法解析，按空处理: id={}", history.getId(), ex);
            }
        }
        return new ActHistoryView(history.getId(), history.getMessage(), history.getReply(),
                history.getOutcome(),
                history.getStepCount() == null ? steps.size() : history.getStepCount(),
                steps, history.getPendingId(), pendingStatus, history.getModel(), history.getCreatedAt());
    }

    // ------------------------------------------------------------------ 模型

    private String complete(ChatModel model, List<Message> messages) {
        try {
            ChatOptions options = ChatOptions.builder()
                    .maxTokens(aiProperties.maxAnswerTokens())
                    .temperature(0.1)
                    .build();
            ChatResponse response = model.call(new Prompt(messages, options));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                throw new IllegalStateException("模型返回为空");
            }
            String text = response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("模型返回为空");
            }
            return text;
        } catch (RuntimeException ex) {
            log.warn("办事模型调用失败", ex);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "AI 服务暂不可用，请稍后重试");
        }
    }

    private String systemPrompt(AuthenticatedUser user, List<AiTool> tools) {
        StringBuilder b = new StringBuilder("""
                你是校园服务平台的“办事窗口”。用户用一句话说明要办的事，你负责选择工具、填好参数。

                只能输出一个 JSON 对象，不要 markdown 代码块，不要任何解释性文字。三种形态：
                1) 需要调用工具：{"action":"call","tool":"工具名","arguments":{...},"message":"一句话说明你在做什么"}
                2) 信息不足需用户补充：{"action":"ask","message":"要问用户的问题"}
                3) 可以答复：{"action":"answer","message":"给用户的答复"}

                规则：
                1. 只能使用“可用工具”中列出的工具名；参数名与类型必须严格符合声明，不要传声明之外的参数。
                2. 不要编造 ID。缺少课程 ID 时，先用查询类工具找到它（如 searchAvailableCourses），找不到再 ask。
                3. 每次只调用一个工具；拿到结果后再决定下一步。
                4. 写操作（sideEffect=true）不会立刻执行，平台会生成确认单请用户确认。你不能声称“已经改好了”。
                5. 不要假装执行过；只有工具结果里出现的内容才可以当作事实。
                6. 用中文，简洁；涉及日期时用 2026-10-28 这样的格式。

                可用工具：
                """);
        b.append(toolCatalog(tools)).append('\n');
        b.append("\n当前上下文：\n").append(context(user));
        return b.toString();
    }

    private String toolCatalog(List<AiTool> tools) {
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (AiTool tool : tools) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.name());
            item.put("title", tool.title());
            item.put("description", tool.description());
            item.put("sideEffect", tool.sideEffect());
            List<Map<String, Object>> params = new ArrayList<>();
            for (ToolParameter p : tool.parameters()) {
                Map<String, Object> param = new LinkedHashMap<>();
                param.put("name", p.name());
                param.put("type", p.type());
                param.put("required", p.required());
                param.put("description", p.description());
                params.add(param);
            }
            item.put("parameters", params);
            catalog.add(item);
        }
        try {
            return objectMapper.writeValueAsString(catalog);
        } catch (Exception ex) {
            log.warn("工具目录序列化失败", ex);
            return "[]";
        }
    }

    private String context(AuthenticatedUser user) {
        StringBuilder b = new StringBuilder();
        b.append("- 用户：").append(user.displayName()).append("（登录名 ").append(user.username()).append("）");
        b.append("，角色 ").append(String.join("/", user.roles())).append('\n');
        b.append("- 今天：").append(LocalDate.now(clock)).append('\n');
        SemesterView semester = null;
        try {
            semester = semesterService.current(user);
        } catch (RuntimeException ex) {
            // 没有课程读权限等情况：办事仍可进行，只是不带学期上下文
            log.debug("读取当前学期失败，忽略上下文", ex);
        }
        if (semester == null) {
            b.append("- 当前学期：无进行中的学期\n");
        } else {
            b.append("- 当前学期：").append(semester.name()).append("（semesterId=").append(semester.id()).append('）');
            b.append("，起止 ").append(semester.startDate()).append(" ~ ").append(semester.endDate());
            long week = ChronoUnit.DAYS.between(semester.startDate(), LocalDate.now(clock)) / 7 + 1;
            if (week >= 1 && week <= 30) {
                b.append("，本周是第 ").append(week).append(" 周");
            }
            b.append("，选课窗口").append(semester.selectionOpen() ? "已开放" : "未开放");
            b.append('\n');
        }
        // 发帖类诉求最常见的缺参就是分类：直接把现有分类摆出来，省一轮追问
        try {
            List<ForumCategoryView> categories = forumCategoryService.list(user, false);
            if (!categories.isEmpty()) {
                b.append("- 论坛分类：").append(String.join("，", categories.stream()
                        .map(c -> c.id() + "=" + c.name()).toList())).append('\n');
            }
        } catch (RuntimeException ex) {
            log.debug("读取论坛分类失败，忽略上下文", ex);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ 解析

    /** 解析模型输出；解析不出 JSON 对象时返回 null（表示“模型在说话，不是指令”）。 */
    private Plan parsePlan(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        String json = text.substring(start, end + 1);
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                return null;
            }
            String action = node.path("action").asText("").trim().toLowerCase(Locale.ROOT);
            if (action.isEmpty()) {
                // 兼容模型直接给 {"tool":...,"arguments":...} 的写法
                action = node.has("tool") ? "call" : "answer";
            }
            JsonNode argsNode = node.get("arguments");
            Map<String, Object> args = argsNode != null && argsNode.isObject()
                    ? objectMapper.convertValue(argsNode, new TypeReference<Map<String, Object>>() {
                    })
                    : Map.of();
            return new Plan(action, node.path("tool").asText(null), args, argsNode,
                    node.path("message").asText(null));
        } catch (Exception ex) {
            log.debug("模型输出不是合法 JSON，按普通文本处理");
            return null;
        }
    }

    private String observation(AiToolCallView call) {
        StringBuilder b = new StringBuilder();
        if (call.resultSummary() != null) {
            b.append("摘要：").append(call.resultSummary()).append('\n');
        }
        if (call.result() != null) {
            try {
                String data = objectMapper.writeValueAsString(call.result());
                b.append("数据：").append(data.length() > MAX_OBSERVATION
                        ? data.substring(0, MAX_OBSERVATION) + "…（已截断）" : data);
            } catch (Exception ex) {
                b.append("数据：无法序列化");
            }
        }
        return b.toString();
    }

    private static boolean canUse(AuthenticatedUser user, AiTool tool) {
        List<String> required = tool.requiredPermissions();
        return required.isEmpty() || required.stream().anyMatch(user::hasPermission);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private record Plan(String action, String tool, Map<String, Object> arguments,
                        JsonNode argumentsNode, String message) {
    }
}
