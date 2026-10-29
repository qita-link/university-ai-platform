package com.campus.platform.scheduling.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.course.mapper.SemesterMapper;
import com.campus.platform.scheduling.dto.CatalogDtos;
import com.campus.platform.scheduling.dto.TermDtos;
import com.campus.platform.scheduling.entity.SchPeriod;
import com.campus.platform.scheduling.entity.SchTimeRule;
import com.campus.platform.scheduling.mapper.SchCourseMapper;
import com.campus.platform.scheduling.mapper.SchPeriodMapper;
import com.campus.platform.scheduling.mapper.SchRoomMapper;
import com.campus.platform.scheduling.mapper.SchTaskMapper;
import com.campus.platform.scheduling.mapper.SchTeachingClassMapper;
import com.campus.platform.scheduling.mapper.SchTimeRuleMapper;
import com.campus.platform.scheduling.solver.model.RuleTarget;
import com.campus.platform.scheduling.solver.model.TimeRuleKind;
import com.campus.platform.scheduling.solver.model.Weeks;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.mapper.SysOrgUnitMapper;
import com.campus.platform.user.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 排课时间规则（禁排/固定/回避/偏好）。
 * <p>
 * 同一对象（教师、班级、教室、课程、任务、全局）的规则是一个整体：接口按对象整体替换，
 * 避免“删一条加一条”造成半更新。教师本人的偏好通过 self 接口维护，只能改自己的规则。
 */
@Service
public class RuleService {

    static final String MODULE = "scheduling";
    private static final Map<String, String> KIND_LABELS = Map.of(
            "FORBIDDEN", "禁排（硬约束）",
            "FIXED", "固定时段（硬约束）",
            "AVAILABLE", "可来时间窗（硬约束）",
            "AVOID", "尽量回避（软约束）",
            "PREFERRED", "优先安排（软约束）");

    private final SchTimeRuleMapper ruleMapper;
    private final SemesterMapper semesterMapper;
    private final SysUserMapper userMapper;
    private final SysOrgUnitMapper orgUnitMapper;
    private final SchRoomMapper roomMapper;
    private final SchCourseMapper courseMapper;
    private final SchTaskMapper taskMapper;
    private final SchTeachingClassMapper teachingClassMapper;
    private final SchPeriodMapper periodMapper;
    private final AuditService auditService;

    public RuleService(SchTimeRuleMapper ruleMapper, SemesterMapper semesterMapper, SysUserMapper userMapper,
                       SysOrgUnitMapper orgUnitMapper, SchRoomMapper roomMapper, SchCourseMapper courseMapper,
                       SchTaskMapper taskMapper, SchTeachingClassMapper teachingClassMapper,
                       SchPeriodMapper periodMapper, AuditService auditService) {
        this.ruleMapper = ruleMapper;
        this.semesterMapper = semesterMapper;
        this.userMapper = userMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.roomMapper = roomMapper;
        this.courseMapper = courseMapper;
        this.taskMapper = taskMapper;
        this.teachingClassMapper = teachingClassMapper;
        this.periodMapper = periodMapper;
        this.auditService = auditService;
    }

    /** 半天口径与节次作息段一致 */
    public static final List<String> SECTIONS = List.of("MORNING", "AFTERNOON", "EVENING");

    public List<CatalogDtos.TimeRuleView> list(AuthenticatedUser user, Long semesterId, String targetType,
                                               Long targetId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        LambdaQueryWrapper<SchTimeRule> w = new LambdaQueryWrapper<SchTimeRule>()
                .eq(semesterId != null, SchTimeRule::getSemesterId, semesterId)
                .eq(targetType != null, SchTimeRule::getTargetType, targetType)
                .eq(targetId != null, SchTimeRule::getTargetId, targetId)
                .orderByAsc(SchTimeRule::getTargetType).orderByAsc(SchTimeRule::getTargetId)
                .orderByAsc(SchTimeRule::getDayOfWeek).orderByAsc(SchTimeRule::getStartPeriod);
        return describe(ruleMapper.selectList(w));
    }

    /** 教师本人维护偏好（PREFERRED/AVOID），只能作用于自己。 */
    @Transactional
    public List<CatalogDtos.TimeRuleView> replaceOwnPreferences(AuthenticatedUser user, Long semesterId,
                                                                List<CatalogDtos.TimeRuleRequest> requests) {
        Permissions.require(user, Permissions.SCHEDULE_PREFERENCE_SELF);
        for (CatalogDtos.TimeRuleRequest req : requests) {
            if (!"TEACHER".equals(req.targetType()) || !Objects.equals(req.targetId(), user.userId())) {
                throw BusinessException.forbidden("只能维护本人的排课偏好");
            }
            if (TimeRuleKind.valueOf(req.kind()).hard()) {
                throw BusinessException.badRequest("教师本人只能维护 AVOID/PREFERRED 软约束");
            }
        }
        return replaceForTarget(user, semesterId, RuleTarget.TEACHER, user.userId(), requests);
    }

    // ------------------------------------------------------------------ 教师时间要求（仅管理员）

    /** 读取某教师在本学期的可来时间窗，折算成「允许的星期 / 半天」。 */
    public TermDtos.TeacherAvailabilityView teacherAvailability(AuthenticatedUser user, long semesterId,
                                                                long teacherId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        requireSemester(semesterId);
        return availabilityView(semesterId, requireTeacher(teacherId));
    }

    /**
     * 设置教师时间要求，只能管理员（{@code schedule:manage}）操作。
     * <p>
     * 语义是「可来时间窗」：外聘教师只能周三来就只勾周三，只能上午来就只勾上午；
     * 允许的星期与半天都为空表示不作限制。这里只替换 AVAILABLE 规则，
     * 教师原有的禁排/固定/偏好规则不受影响（求解时两者取交集）。
     */
    @Transactional
    public TermDtos.TeacherAvailabilityView putTeacherAvailability(AuthenticatedUser user, long semesterId,
                                                                  long teacherId,
                                                                  TermDtos.TeacherAvailabilityRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        requireSemester(semesterId);
        SysUser teacher = requireTeacher(teacherId);

        List<Integer> days = normalizeDays(req == null ? null : req.days());
        List<String> sections = normalizeSections(req == null ? null : req.sections());

        ruleMapper.delete(new LambdaQueryWrapper<SchTimeRule>()
                .eq(SchTimeRule::getSemesterId, semesterId)
                .eq(SchTimeRule::getTargetType, RuleTarget.TEACHER.name())
                .eq(SchTimeRule::getTargetId, teacherId)
                .eq(SchTimeRule::getRuleKind, TimeRuleKind.AVAILABLE.name()));

        if (!days.isEmpty() || !sections.isEmpty()) {
            Map<String, int[]> ranges = sectionRanges(semesterId);
            List<Integer> useDays = days.isEmpty() ? List.of(1, 2, 3, 4, 5, 6, 7) : days;
            List<String> useSections = sections.isEmpty() ? SECTIONS : sections;
            int start = Integer.MAX_VALUE;
            int end = 0;
            for (String s : useSections) {
                int[] r = ranges.get(s);
                if (r == null) {
                    continue;
                }
                start = Math.min(start, r[0]);
                end = Math.max(end, r[1]);
            }
            if (start == Integer.MAX_VALUE) {
                throw BusinessException.badRequest("本学期还没有节次作息，无法设置教师时间要求");
            }
            for (Integer day : useDays) {
                SchTimeRule row = new SchTimeRule();
                row.setSemesterId(semesterId);
                row.setTargetType(RuleTarget.TEACHER.name());
                row.setTargetId(teacherId);
                row.setRuleKind(TimeRuleKind.AVAILABLE.name());
                row.setDayOfWeek(day);
                row.setStartPeriod(start);
                row.setEndPeriod(end);
                row.setStartWeek(1);
                row.setEndWeek(30);
                row.setWeight(1);
                row.setNote("教师时间要求：可来时间窗");
                row.setCreatedBy(user.userId());
                row.setUpdatedBy(user.userId());
                ruleMapper.insert(row);
            }
        }
        audit(user, "SCHEDULING_TEACHER_AVAILABILITY", teacherId, Map.of("days", days, "sections", sections));
        return availabilityView(semesterId, teacher);
    }

    private TermDtos.TeacherAvailabilityView availabilityView(long semesterId, SysUser teacher) {
        List<SchTimeRule> rows = ruleMapper.selectList(new LambdaQueryWrapper<SchTimeRule>()
                .eq(SchTimeRule::getSemesterId, semesterId)
                .eq(SchTimeRule::getTargetType, RuleTarget.TEACHER.name())
                .eq(SchTimeRule::getTargetId, teacher.getId())
                .eq(SchTimeRule::getRuleKind, TimeRuleKind.AVAILABLE.name()));
        if (rows.isEmpty()) {
            return new TermDtos.TeacherAvailabilityView(semesterId, teacher.getId(), teacher.getDisplayName(),
                    List.of(), List.of());
        }
        List<Integer> days = rows.stream().map(SchTimeRule::getDayOfWeek).filter(Objects::nonNull)
                .distinct().sorted().toList();
        Map<String, int[]> ranges = sectionRanges(semesterId);
        List<String> sections = new ArrayList<>();
        for (String s : SECTIONS) {
            int[] r = ranges.get(s);
            if (r == null) {
                continue;
            }
            boolean covered = rows.stream().allMatch(row -> row.getStartPeriod() <= r[0] && r[1] <= row.getEndPeriod());
            if (covered) {
                sections.add(s);
            }
        }
        return new TermDtos.TeacherAvailabilityView(semesterId, teacher.getId(), teacher.getDisplayName(),
                days, sections);
    }

    /** 各作息段的节次区间（MORNING 1-4 / AFTERNOON 5-8 / EVENING 9-12 之类，按实际作息表算）。 */
    private Map<String, int[]> sectionRanges(long semesterId) {
        Map<String, int[]> out = new LinkedHashMap<>();
        List<SchPeriod> periods = periodMapper.selectList(new LambdaQueryWrapper<SchPeriod>()
                .eq(SchPeriod::getSemesterId, semesterId).orderByAsc(SchPeriod::getPeriodNo));
        for (SchPeriod p : periods) {
            String section = p.getSection() == null || p.getSection().isBlank() ? "MORNING" : p.getSection();
            int[] r = out.get(section);
            if (r == null) {
                out.put(section, new int[]{p.getPeriodNo(), p.getPeriodNo()});
            } else {
                r[0] = Math.min(r[0], p.getPeriodNo());
                r[1] = Math.max(r[1], p.getPeriodNo());
            }
        }
        return out;
    }

    private static List<Integer> normalizeDays(List<Integer> days) {
        if (days == null) {
            return List.of();
        }
        return days.stream().filter(Objects::nonNull).filter(d -> d >= 1 && d <= 7).distinct().sorted().toList();
    }

    private static List<String> normalizeSections(List<String> sections) {
        if (sections == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : sections) {
            if (s == null || s.isBlank()) {
                continue;
            }
            String up = s.trim().toUpperCase(Locale.ROOT);
            if (!SECTIONS.contains(up)) {
                throw BusinessException.badRequest("半天只能是 MORNING/AFTERNOON/EVENING");
            }
            if (!out.contains(up)) {
                out.add(up);
            }
        }
        out.sort(Comparator.comparingInt(SECTIONS::indexOf));
        return out;
    }

    private SysUser requireTeacher(long teacherId) {
        SysUser teacher = userMapper.selectById(teacherId);
        if (teacher == null) {
            throw BusinessException.notFound("教师");
        }
        return teacher;
    }

    /** 按对象整体替换规则。 */
    @Transactional
    public List<CatalogDtos.TimeRuleView> replaceForTarget(AuthenticatedUser user, long semesterId,
                                                           RuleTarget target, Long targetId,
                                                           List<CatalogDtos.TimeRuleRequest> requests) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        requireSemester(semesterId);
        if (target != RuleTarget.GLOBAL && targetId == null) {
            throw BusinessException.badRequest("targetType=" + target + " 时必须提供 targetId");
        }
        validateTarget(target, targetId);
        ruleMapper.delete(new LambdaQueryWrapper<SchTimeRule>()
                .eq(SchTimeRule::getSemesterId, semesterId)
                .eq(SchTimeRule::getTargetType, target.name())
                .eq(SchTimeRule::getTargetId, targetId == null ? 0L : targetId));
        List<SchTimeRule> rows = new ArrayList<>();
        if (requests != null) {
            for (CatalogDtos.TimeRuleRequest req : requests) {
                rows.add(toEntity(user, semesterId, target, targetId, req));
            }
        }
        rows.forEach(ruleMapper::insert);
        audit(user, "SCHEDULING_RULE_REPLACE", semesterId, Map.of("targetType", target.name(),
                "targetId", targetId == null ? 0L : targetId, "count", rows.size()));
        return describe(rows);
    }

    @Transactional
    public CatalogDtos.TimeRuleView create(AuthenticatedUser user, CatalogDtos.TimeRuleRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        requireSemester(req.semesterId());
        RuleTarget target = parseTarget(req.targetType());
        validateTarget(target, req.targetId());
        SchTimeRule row = toEntity(user, req.semesterId(), target, req.targetId(), req);
        ruleMapper.insert(row);
        audit(user, "SCHEDULING_RULE_CREATE", row.getId(), Map.of("targetType", target.name(),
                "kind", row.getRuleKind()));
        return describe(List.of(row)).get(0);
    }

    @Transactional
    public CatalogDtos.TimeRuleView update(AuthenticatedUser user, long id, CatalogDtos.TimeRuleRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        SchTimeRule existing = ruleMapper.selectById(id);
        if (existing == null) {
            throw BusinessException.notFound("时间规则");
        }
        RuleTarget target = parseTarget(req.targetType());
        validateTarget(target, req.targetId());
        SchTimeRule row = toEntity(user, req.semesterId(), target, req.targetId(), req);
        row.setId(id);
        ruleMapper.updateById(row);
        audit(user, "SCHEDULING_RULE_UPDATE", id, Map.of("targetType", target.name()));
        return describe(List.of(row)).get(0);
    }

    @Transactional
    public void delete(AuthenticatedUser user, long id) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        SchTimeRule existing = ruleMapper.selectById(id);
        if (existing == null) {
            throw BusinessException.notFound("时间规则");
        }
        ruleMapper.deleteById(id);
        audit(user, "SCHEDULING_RULE_DELETE", id, Map.of("kind", existing.getRuleKind()));
    }

    /** 规则模板：常用禁排/偏好，管理员可一键套用后微调。 */
    public List<Map<String, Object>> templates(AuthenticatedUser user) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(template("LUNCH_BREAK", "午休禁排", "FORBIDDEN", null, 5, 5, "12:00-13:30 不排课（下午第一节前）"));
        out.add(template("EVENING_LIMIT", "晚间不排课", "FORBIDDEN", null, 9, 12, "第 9 节之后不排理论课"));
        out.add(template("FRIDAY_AFTERNOON", "周五下午回避", "AVOID", 5, 5, 8, "尽量不排周五下午"));
        out.add(template("NO_EARLY_MORNING", "减少早八", "AVOID", null, 1, 1, "尽量减少第 1 节"));
        out.add(template("MORNING_PREFERRED", "优先上午", "PREFERRED", null, 1, 4, "优先安排在上午"));
        out.add(template("WEEKEND_FORBIDDEN", "周末不排课", "FORBIDDEN", null, 1, 16, "周六周日不排课"));
        return out;
    }

    public List<SchTimeRule> rowsForProblem(long semesterId) {
        return ruleMapper.selectList(new LambdaQueryWrapper<SchTimeRule>()
                .eq(SchTimeRule::getSemesterId, semesterId));
    }

    public List<CatalogDtos.TimeRuleView> forTarget(long semesterId, RuleTarget target, Long targetId) {
        return describe(ruleMapper.selectList(new LambdaQueryWrapper<SchTimeRule>()
                .eq(SchTimeRule::getSemesterId, semesterId)
                .eq(SchTimeRule::getTargetType, target.name())
                .eq(SchTimeRule::getTargetId, targetId == null ? 0L : targetId)));
    }

    public List<CatalogDtos.TimeRuleView> describe(List<SchTimeRule> rows) {
        Map<Long, String> names = new LinkedHashMap<>();
        Map<String, List<Long>> idsByType = rows.stream().collect(Collectors.groupingBy(SchTimeRule::getTargetType,
                Collectors.mapping(r -> r.getTargetId() == null ? 0L : r.getTargetId(), Collectors.toList())));
        idsByType.forEach((type, ids) -> {
            List<Long> nonZero = ids.stream().filter(i -> i != 0L).distinct().toList();
            if (nonZero.isEmpty()) {
                return;
            }
            switch (type) {
                case "TEACHER" -> userMapper.selectBatchIds(nonZero)
                        .forEach(u -> names.put(u.getId(), u.getDisplayName()));
                case "CLASS" -> orgUnitMapper.selectBatchIds(nonZero)
                        .forEach(o -> names.put(o.getId(), o.getName()));
                case "TEACHING_CLASS" -> teachingClassMapper.selectBatchIds(nonZero)
                        .forEach(t -> names.put(t.getId(), t.getName()));
                case "ROOM" -> roomMapper.selectBatchIds(nonZero)
                        .forEach(r -> names.put(r.getId(), r.getCode() + " " + r.getName()));
                case "COURSE" -> courseMapper.selectBatchIds(nonZero)
                        .forEach(c -> names.put(c.getId(), c.getCode() + " " + c.getName()));
                case "TASK" -> taskMapper.selectBatchIds(nonZero)
                        .forEach(t -> names.put(t.getId(), t.getCode()));
                default -> {
                }
            }
        });
        return rows.stream()
                .sorted(Comparator.comparing(SchTimeRule::getTargetType)
                        .thenComparing(r -> r.getTargetId() == null ? 0L : r.getTargetId())
                        .thenComparing(r -> r.getDayOfWeek() == null ? 0 : r.getDayOfWeek())
                        .thenComparing(SchTimeRule::getStartPeriod))
                .map(r -> new CatalogDtos.TimeRuleView(r.getId(), r.getSemesterId(), r.getTargetType(),
                        r.getTargetId(), names.get(r.getTargetId()), r.getRuleKind(), r.getDayOfWeek(),
                        r.getStartPeriod(), r.getEndPeriod(), r.getStartWeek(), r.getEndWeek(), r.getWeight(),
                        r.getNote(), weekList(r), timeText(r))).toList();
    }

    private SchTimeRule toEntity(AuthenticatedUser user, long semesterId, RuleTarget target, Long targetId,
                                 CatalogDtos.TimeRuleRequest req) {
        if (req.startPeriod() > req.endPeriod()) {
            throw BusinessException.badRequest("开始节次不能大于结束节次");
        }
        TimeRuleKind kind;
        try {
            kind = TimeRuleKind.valueOf(req.kind());
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest("规则类型必须是 FORBIDDEN/FIXED/AVAILABLE/AVOID/PREFERRED");
        }
        if (kind == TimeRuleKind.FIXED && target != RuleTarget.COURSE && target != RuleTarget.TASK) {
            throw BusinessException.badRequest("固定时段规则只能作用于 COURSE 或 TASK");
        }
        if (kind == TimeRuleKind.AVAILABLE && target != RuleTarget.TEACHER) {
            throw BusinessException.badRequest("可来时间窗只支持作用于 TEACHER（教师时间要求）");
        }
        int startWeek = req.startWeek() == null ? 1 : req.startWeek();
        int endWeek = req.endWeek() == null ? 30 : req.endWeek();
        if (req.weeks() != null && !req.weeks().isEmpty()) {
            startWeek = req.weeks().stream().min(Integer::compareTo).orElse(1);
            endWeek = req.weeks().stream().max(Integer::compareTo).orElse(30);
        }
        if (endWeek < startWeek) {
            throw BusinessException.badRequest("开始周不能大于结束周");
        }
        SchTimeRule row = new SchTimeRule();
        row.setSemesterId(semesterId);
        row.setTargetType(target.name());
        row.setTargetId(target == RuleTarget.GLOBAL ? null : targetId);
        row.setRuleKind(kind.name());
        row.setDayOfWeek(req.dayOfWeek());
        row.setStartPeriod(req.startPeriod());
        row.setEndPeriod(req.endPeriod());
        row.setStartWeek(startWeek);
        row.setEndWeek(endWeek);
        row.setWeight(kind.hard() ? 1 : (req.weight() == null ? 3 : req.weight()));
        row.setNote(req.note());
        row.setCreatedBy(user.userId());
        row.setUpdatedBy(user.userId());
        if (req.weeks() != null && !req.weeks().isEmpty() && req.weeks().size() < endWeek - startWeek + 1) {
            // 显式周次与连续区间不一致时，用逐周规则表达（同一区间拆成多条）
            throw BusinessException.badRequest("暂不支持不连续的周次集合，请拆分为多条规则或使用 startWeek/endWeek");
        }
        return row;
    }

    private void validateTarget(RuleTarget target, Long targetId) {
        if (target == RuleTarget.GLOBAL) {
            return;
        }
        if (targetId == null) {
            throw BusinessException.badRequest("必须指定 targetId");
        }
        boolean exists = switch (target) {
            case TEACHER -> userMapper.selectById(targetId) != null;
            case CLASS -> {
                SysOrgUnit org = orgUnitMapper.selectById(targetId);
                yield org != null && org.getType() != null && org.getType().name().equals("CLASS");
            }
            case TEACHING_CLASS -> teachingClassMapper.selectById(targetId) != null;
            case ROOM -> roomMapper.selectById(targetId) != null;
            case COURSE -> courseMapper.selectById(targetId) != null;
            case TASK -> taskMapper.selectById(targetId) != null;
            default -> true;
        };
        if (!exists) {
            throw BusinessException.badRequest("规则作用对象不存在：" + target + " #" + targetId);
        }
    }

    private void requireSemester(long semesterId) {
        if (semesterMapper.selectById(semesterId) == null) {
            throw BusinessException.notFound("学期");
        }
    }

    private static RuleTarget parseTarget(String value) {
        try {
            return RuleTarget.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest("targetType 必须是 GLOBAL/TEACHER/CLASS/TEACHING_CLASS/ROOM/COURSE/TASK");
        }
    }

    static List<Integer> weekList(SchTimeRule r) {
        long mask = Weeks.range(r.getStartWeek() == null ? 1 : r.getStartWeek(),
                r.getEndWeek() == null ? 30 : r.getEndWeek(), com.campus.platform.course.entity.WeekParity.ALL);
        List<Integer> weeks = new ArrayList<>();
        for (int w = 1; w <= Weeks.MAX_WEEKS; w++) {
            if (Weeks.has(mask, w)) {
                weeks.add(w);
            }
        }
        return weeks;
    }

    static String timeText(SchTimeRule r) {
        String day = r.getDayOfWeek() == null ? "每天" : "周" + r.getDayOfWeek();
        String periods = r.getStartPeriod().equals(r.getEndPeriod())
                ? "第" + r.getStartPeriod() + "节"
                : "第" + r.getStartPeriod() + "-" + r.getEndPeriod() + "节";
        String weeks = "第" + (r.getStartWeek() == null ? 1 : r.getStartWeek()) + "-"
                + (r.getEndWeek() == null ? 30 : r.getEndWeek()) + "周";
        return day + " " + periods + " " + weeks;
    }

    private static Map<String, Object> template(String code, String name, String kind, Integer day, int start,
                                                int end, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("kind", kind);
        m.put("kindLabel", KIND_LABELS.get(kind));
        m.put("dayOfWeek", day);
        m.put("startPeriod", start);
        m.put("endPeriod", end);
        m.put("description", description);
        return m;
    }

    private void audit(AuthenticatedUser user, String action, Long targetId, Map<String, ?> detail) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("time_rule", targetId);
        detail.forEach(b::detail);
        auditService.record(b.build());
    }
}
