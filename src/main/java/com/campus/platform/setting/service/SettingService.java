package com.campus.platform.setting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.setting.dto.SettingView;
import com.campus.platform.setting.entity.PlatformSetting;
import com.campus.platform.setting.mapper.PlatformSettingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 平台设置的读写与请假审批流程解释。
 * <p>
 * 设置值全部以字符串存储，读取时按需转换；写入前做白名单与取值校验，
 * 避免管理员把审批流程配成「无人可审」。
 */
@Service
public class SettingService {

    static final String MODULE = "setting";

    public static final String LEAVE_TEACHER_KEY = "leave.approval.teacher";
    public static final String LEAVE_COUNSELOR_KEY = "leave.approval.counselor";
    public static final String LEAVE_ORDER_KEY = "leave.approval.order";
    public static final String EVALUATION_ANONYMOUS_KEY = "evaluation.anonymous";

    /** 审批角色顺序里的合法取值。 */
    public static final String ROLE_TEACHER = "TEACHER";
    public static final String ROLE_COUNSELOR = "COUNSELOR";

    private final PlatformSettingMapper mapper;
    private final AuditService auditService;

    public SettingService(PlatformSettingMapper mapper, AuditService auditService) {
        this.mapper = mapper;
        this.auditService = auditService;
    }

    public List<SettingView> list() {
        return mapper.selectList(new LambdaQueryWrapper<PlatformSetting>().orderByAsc(PlatformSetting::getSettingKey))
                .stream()
                .map(s -> new SettingView(s.getSettingKey(), s.getSettingValue(), s.getDescription()))
                .toList();
    }

    public Map<String, String> asMap() {
        return mapper.selectList(null).stream()
                .collect(Collectors.toMap(PlatformSetting::getSettingKey, PlatformSetting::getSettingValue));
    }

    public String get(String key, String fallback) {
        PlatformSetting setting = mapper.selectById(key);
        return setting == null || setting.getSettingValue() == null ? fallback : setting.getSettingValue();
    }

    public boolean getBool(String key, boolean fallback) {
        String value = get(key, null);
        if (value == null) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim());
    }

    @Transactional
    public SettingView update(AuthenticatedUser user, String key, String rawValue) {
        Permissions.require(user, "system:config");
        PlatformSetting setting = mapper.selectById(key);
        if (setting == null) {
            throw BusinessException.notFound("设置项");
        }
        String value = normalize(key, rawValue);
        if (LEAVE_ORDER_KEY.equals(key) || LEAVE_TEACHER_KEY.equals(key) || LEAVE_COUNSELOR_KEY.equals(key)) {
            // 三项互相约束：先算出「更新后的完整流程」再整体校验
            Map<String, String> current = asMap();
            current.put(key, value);
            requireConsistent(current);
        }
        setting.setSettingValue(value);
        mapper.updateById(setting);
        auditService.record(AuditEvent.builder(MODULE, "SETTING_UPDATE")
                .target("PLATFORM_SETTING", null)
                .detail("key", key)
                .detail("value", value)
                .build());
        return new SettingView(setting.getSettingKey(), setting.getSettingValue(), setting.getDescription());
    }

    /** 请假审批流程（按当前设置解析出实际生效的顺序）。 */
    public LeaveFlow leaveFlow() {
        Map<String, String> values = asMap();
        return resolve(values);
    }

    private LeaveFlow resolve(Map<String, String> values) {
        boolean teacher = bool(values.get(LEAVE_TEACHER_KEY), true);
        boolean counselor = bool(values.get(LEAVE_COUNSELOR_KEY), true);
        List<String> wanted = parseOrder(values.get(LEAVE_ORDER_KEY));
        List<String> stages = new ArrayList<>();
        for (String role : wanted) {
            if ((ROLE_TEACHER.equals(role) && teacher) || (ROLE_COUNSELOR.equals(role) && counselor)) {
                stages.add(role);
            }
        }
        // 配置缺项时补齐，保证至少有一级审批
        if (teacher && !stages.contains(ROLE_TEACHER)) {
            stages.add(ROLE_TEACHER);
        }
        if (counselor && !stages.contains(ROLE_COUNSELOR)) {
            stages.add(ROLE_COUNSELOR);
        }
        if (stages.isEmpty()) {
            stages.add(ROLE_TEACHER);
        }
        return new LeaveFlow(teacher, counselor, List.copyOf(stages));
    }

    private void requireConsistent(Map<String, String> values) {
        LeaveFlow flow = resolve(values);
        if (flow.stages().isEmpty()) {
            throw new BusinessException(com.campus.platform.common.exception.ErrorCode.VALIDATION_FAILED,
                    "至少需要保留一级审批人");
        }
    }

    private static List<String> parseOrder(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String role = part.trim().toUpperCase(Locale.ROOT);
            if (ROLE_TEACHER.equals(role) || ROLE_COUNSELOR.equals(role)) {
                ordered.add(role);
            }
        }
        return List.copyOf(ordered);
    }

    private static boolean bool(String raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        return "true".equalsIgnoreCase(raw.trim()) || "1".equals(raw.trim());
    }

    private String normalize(String key, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (LEAVE_TEACHER_KEY.equals(key) || LEAVE_COUNSELOR_KEY.equals(key)) {
            boolean flag = "true".equalsIgnoreCase(value) || "1".equals(value);
            boolean other = LEAVE_TEACHER_KEY.equals(key)
                    ? getBool(LEAVE_COUNSELOR_KEY, true)
                    : getBool(LEAVE_TEACHER_KEY, true);
            if (!flag && !other) {
                throw new BusinessException(com.campus.platform.common.exception.ErrorCode.VALIDATION_FAILED,
                        "任课教师与导员审批不能同时关闭");
            }
            return Boolean.toString(flag);
        }
        if (LEAVE_ORDER_KEY.equals(key)) {
            List<String> roles = parseOrder(value);
            if (roles.size() < 2) {
                throw new BusinessException(com.campus.platform.common.exception.ErrorCode.VALIDATION_FAILED,
                        "审批顺序需同时包含任课教师与导员");
            }
            return String.join(",", roles);
        }
        return value;
    }

    /** 请假审批流程快照。 */
    public record LeaveFlow(boolean teacherRequired, boolean counselorRequired, List<String> stages) {

        public int totalStages() {
            return stages.size();
        }
    }
}
