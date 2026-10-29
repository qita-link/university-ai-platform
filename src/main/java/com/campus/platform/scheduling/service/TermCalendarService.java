package com.campus.platform.scheduling.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.course.entity.Semester;
import com.campus.platform.course.entity.SemesterStatus;
import com.campus.platform.course.entity.WeekParity;
import com.campus.platform.course.mapper.SemesterMapper;
import com.campus.platform.scheduling.dto.TermDtos;
import com.campus.platform.scheduling.entity.SchCalendarDate;
import com.campus.platform.scheduling.entity.SchCalendarWeek;
import com.campus.platform.scheduling.entity.SchConstraintConfig;
import com.campus.platform.scheduling.entity.SchPeriod;
import com.campus.platform.scheduling.entity.SchSemesterConfig;
import com.campus.platform.scheduling.mapper.SchCalendarDateMapper;
import com.campus.platform.scheduling.mapper.SchCalendarWeekMapper;
import com.campus.platform.scheduling.mapper.SchConstraintConfigMapper;
import com.campus.platform.scheduling.mapper.SchPeriodMapper;
import com.campus.platform.scheduling.mapper.SchSemesterConfigMapper;
import com.campus.platform.scheduling.mapper.SchVersionMapper;
import com.campus.platform.scheduling.entity.SchVersion;
import com.campus.platform.scheduling.entity.VersionStatus;
import com.campus.platform.scheduling.solver.model.ConstraintCode;
import com.campus.platform.scheduling.solver.model.Weeks;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 学期排课配置与教学日历。
 * <p>
 * 学期本身复用 {@code semester}，本服务只维护排课口径：教学周数、第 1 周周一、教学日、每日节次、
 * 节次作息、特殊教学周与特殊日期、软约束权重。所有写操作需要 {@code schedule:manage}。
 */
@Service
public class TermCalendarService {

    static final String MODULE = "scheduling";
    static final Set<String> SECTIONS = Set.of("MORNING", "AFTERNOON", "EVENING");

    private final SemesterMapper semesterMapper;
    private final SchSemesterConfigMapper configMapper;
    private final SchPeriodMapper periodMapper;
    private final SchCalendarWeekMapper weekMapper;
    private final SchCalendarDateMapper dateMapper;
    private final SchConstraintConfigMapper constraintMapper;
    private final SchVersionMapper versionMapper;
    private final AuditService auditService;
    private final Clock clock;

    public TermCalendarService(SemesterMapper semesterMapper, SchSemesterConfigMapper configMapper,
                               SchPeriodMapper periodMapper, SchCalendarWeekMapper weekMapper,
                               SchCalendarDateMapper dateMapper, SchConstraintConfigMapper constraintMapper,
                               SchVersionMapper versionMapper, AuditService auditService, Clock clock) {
        this.semesterMapper = semesterMapper;
        this.configMapper = configMapper;
        this.periodMapper = periodMapper;
        this.weekMapper = weekMapper;
        this.dateMapper = dateMapper;
        this.constraintMapper = constraintMapper;
        this.versionMapper = versionMapper;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 学期

    public List<TermDtos.TermView> list(AuthenticatedUser user) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        List<Semester> semesters = semesterMapper.selectList(new LambdaQueryWrapper<Semester>()
                .orderByDesc(Semester::getStartDate).orderByDesc(Semester::getId));
        Map<Long, SchSemesterConfig> configs = configMapper.selectList(null).stream()
                .collect(Collectors.toMap(SchSemesterConfig::getSemesterId, c -> c, (a, b) -> a));
        return semesters.stream().map(s -> view(s, configs.get(s.getId()))).toList();
    }

    public TermDtos.TermView get(AuthenticatedUser user, long semesterId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        return view(requireSemester(semesterId), configMapper.selectById(semesterId));
    }

    @Transactional
    public TermDtos.TermView create(AuthenticatedUser user, TermDtos.TermCreateRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        if (req.endDate().isBefore(req.startDate())) {
            throw BusinessException.badRequest("学期结束日期不能早于开始日期");
        }
        validateFirstWeek(req.firstWeekStart(), req.teachingWeeks());
        String code = req.code().trim();
        Long exists = semesterMapper.selectCount(new LambdaQueryWrapper<Semester>().eq(Semester::getCode, code));
        if (exists != null && exists > 0) {
            throw BusinessException.conflict("学期编码 " + code + " 已存在");
        }
        Semester semester = new Semester();
        semester.setCode(code);
        semester.setName(req.name().trim());
        semester.setStartDate(req.startDate());
        semester.setEndDate(req.endDate());
        // 选课窗口与排课无关，给一个默认值以满足既有表约束（开学前 30 天至开学前一天）
        semester.setSelectionStartAt(req.startDate().minusDays(30).atStartOfDay());
        semester.setSelectionEndAt(req.startDate().minusDays(1).atTime(23, 59));
        semester.setStatus(SemesterStatus.DRAFT);
        semesterMapper.insert(semester);

        SchSemesterConfig config = new SchSemesterConfig();
        config.setSemesterId(semester.getId());
        config.setTeachingWeeks(req.teachingWeeks());
        config.setFirstWeekStart(req.firstWeekStart());
        config.setTeachingDays(joinDays(req.teachingDays()));
        config.setPeriodsPerDay(req.periodsPerDay());
        config.setDefaultTravelMinutes(30);
        config.setMaxRoomCandidates(12);
        config.setCreatedBy(user.userId());
        config.setUpdatedBy(user.userId());
        configMapper.insert(config);
        ensureDefaultPeriods(semester.getId(), req.periodsPerDay());

        audit(user, "SCHEDULING_TERM_CREATE", semester.getId(), Map.of("code", code,
                "weeks", req.teachingWeeks(), "periodsPerDay", req.periodsPerDay()));
        return view(semester, config);
    }

    @Transactional
    public TermDtos.ConfigView putConfig(AuthenticatedUser user, long semesterId, TermDtos.ConfigRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        requireSemester(semesterId);
        assertNoPublishedVersion(semesterId, "修改排课配置");
        validateFirstWeek(req.firstWeekStart(), req.teachingWeeks());
        SchSemesterConfig config = configMapper.selectById(semesterId);
        boolean created = false;
        if (config == null) {
            config = new SchSemesterConfig();
            config.setSemesterId(semesterId);
            created = true;
        }
        config.setTeachingWeeks(req.teachingWeeks());
        config.setFirstWeekStart(req.firstWeekStart());
        config.setTeachingDays(joinDays(req.teachingDays()));
        config.setPeriodsPerDay(req.periodsPerDay());
        config.setDefaultTravelMinutes(req.defaultTravelMinutes() == null ? 30 : req.defaultTravelMinutes());
        config.setMaxRoomCandidates(req.maxRoomCandidates() == null ? 12 : req.maxRoomCandidates());
        config.setUpdatedBy(user.userId());
        if (created) {
            config.setCreatedBy(user.userId());
            configMapper.insert(config);
        } else {
            configMapper.updateById(config);
        }
        ensureDefaultPeriods(semesterId, req.periodsPerDay());
        audit(user, "SCHEDULING_TERM_CONFIG", semesterId, Map.of("weeks", req.teachingWeeks(),
                "days", req.teachingDays().toString(), "periodsPerDay", req.periodsPerDay()));
        return configView(config);
    }

    // ------------------------------------------------------------------ 节次

    public List<TermDtos.PeriodView> periods(AuthenticatedUser user, long semesterId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        return periodViews(semesterId);
    }

    /**
     * 只读节次作息，不做权限判断。
     * <p>
     * 供「本人课表」一类只需 timetable:read 的接口复用：学生也要在课表左侧看到上课时间，
     * 但他们没有 schedule:read，不能走 {@link #periods(AuthenticatedUser, long)}。
     */
    public List<TermDtos.PeriodView> periodViews(long semesterId) {
        return periodMapper.selectList(new LambdaQueryWrapper<SchPeriod>()
                        .eq(SchPeriod::getSemesterId, semesterId).orderByAsc(SchPeriod::getPeriodNo))
                .stream().map(p -> new TermDtos.PeriodView(p.getPeriodNo(), p.getStartTime(), p.getEndTime(),
                        p.getSection())).toList();
    }

    /**
     * 节次作息（带兜底）：本学期没维护作息时，沿用一个「配置过作息」的学期。
     * <p>
     * 作息表是全校统一的（几点打铃不随学期变），而老学期常常是从旧系统导进来的、没有 sch_period，
     * 这时课表左侧会整列空着，看起来像坏了。兜底顺序：进行中的学期 → 开始日期最晚的学期。
     * 学期自己配了作息就一律以自己为准。
     */
    public List<TermDtos.PeriodView> periodViewsWithFallback(long semesterId) {
        List<TermDtos.PeriodView> own = periodViews(semesterId);
        if (!own.isEmpty()) {
            return own;
        }
        List<Long> candidates = periodMapper.selectList(new LambdaQueryWrapper<SchPeriod>()
                        .select(SchPeriod::getSemesterId).groupBy(SchPeriod::getSemesterId))
                .stream().map(SchPeriod::getSemesterId).filter(Objects::nonNull).distinct().toList();
        Long best = null;
        boolean bestActive = false;
        LocalDate bestStart = null;
        for (Long candidate : candidates) {
            Semester semester = semesterMapper.selectById(candidate);
            if (semester == null) {
                continue;
            }
            boolean active = semester.getStatus() == SemesterStatus.ACTIVE;
            LocalDate start = semester.getStartDate();
            boolean better;
            if (best == null) {
                better = true;
            } else if (active != bestActive) {
                better = active;
            } else {
                better = start != null && (bestStart == null || start.isAfter(bestStart));
            }
            if (better) {
                best = candidate;
                bestActive = active;
                bestStart = start;
            }
        }
        return best == null ? List.of() : periodViews(best);
    }

    @Transactional
    public List<TermDtos.PeriodView> putPeriods(AuthenticatedUser user, long semesterId,
                                                TermDtos.PeriodRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        assertNoPublishedVersion(semesterId, "修改节次作息");
        SchSemesterConfig config = requireConfig(semesterId);
        List<TermDtos.PeriodView> sorted = new ArrayList<>(req.periods());
        sorted.sort(Comparator.comparing(TermDtos.PeriodView::periodNo));
        Set<Integer> seen = new TreeSet<>();
        LocalTime prevEnd = null;
        for (TermDtos.PeriodView p : sorted) {
            if (!seen.add(p.periodNo())) {
                throw BusinessException.badRequest("节次号 " + p.periodNo() + " 重复");
            }
            if (!p.startTime().isBefore(p.endTime())) {
                throw BusinessException.badRequest("第 " + p.periodNo() + " 节的开始时间必须早于结束时间");
            }
            if (!SECTIONS.contains(p.section())) {
                throw BusinessException.badRequest("第 " + p.periodNo() + " 节的作息段必须是 MORNING/AFTERNOON/EVENING");
            }
            if (prevEnd != null && p.startTime().isBefore(prevEnd)) {
                throw BusinessException.badRequest("第 " + p.periodNo() + " 节与上一节时间重叠");
            }
            prevEnd = p.endTime();
        }
        if (sorted.size() > config.getPeriodsPerDay()) {
            throw BusinessException.badRequest("节次数量 " + sorted.size() + " 超过每日节数配置 "
                    + config.getPeriodsPerDay());
        }
        periodMapper.delete(new LambdaQueryWrapper<SchPeriod>().eq(SchPeriod::getSemesterId, semesterId));
        for (TermDtos.PeriodView p : sorted) {
            SchPeriod row = new SchPeriod();
            row.setSemesterId(semesterId);
            row.setPeriodNo(p.periodNo());
            row.setStartTime(p.startTime());
            row.setEndTime(p.endTime());
            row.setSection(p.section());
            periodMapper.insert(row);
        }
        audit(user, "SCHEDULING_PERIOD_REPLACE", semesterId, Map.of("count", sorted.size()));
        return periods(user, semesterId);
    }

    /** 学期未配置节次时生成默认作息，保证最小可用（可在 PUT /periods 覆盖）。 */
    private void ensureDefaultPeriods(long semesterId, int periodsPerDay) {
        Long count = periodMapper.selectCount(new LambdaQueryWrapper<SchPeriod>()
                .eq(SchPeriod::getSemesterId, semesterId));
        if (count != null && count > 0) {
            return;
        }
        int[] starts = {480, 530, 580, 630, 810, 860, 910, 960, 1010, 1050, 1100, 1150, 1200, 1250, 1300, 1350};
        for (int i = 0; i < Math.min(periodsPerDay, starts.length); i++) {
            SchPeriod row = new SchPeriod();
            row.setSemesterId(semesterId);
            row.setPeriodNo(i + 1);
            row.setStartTime(LocalTime.of(starts[i] / 60, starts[i] % 60));
            row.setEndTime(LocalTime.of((starts[i] + 45) / 60, (starts[i] + 45) % 60));
            row.setSection(i < 4 ? "MORNING" : i < 8 ? "AFTERNOON" : "EVENING");
            periodMapper.insert(row);
        }
    }

    // ------------------------------------------------------------------ 教学日历

    public TermDtos.CalendarView calendar(AuthenticatedUser user, long semesterId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        List<TermDtos.WeekView> weeks = weekMapper.selectList(new LambdaQueryWrapper<SchCalendarWeek>()
                        .eq(SchCalendarWeek::getSemesterId, semesterId).orderByAsc(SchCalendarWeek::getWeekNo))
                .stream().map(w -> new TermDtos.WeekView(w.getWeekNo(), w.getWeekType(), w.getTeachingAllowed(),
                        w.getNote())).toList();
        List<TermDtos.DateView> dates = dateMapper.selectList(new LambdaQueryWrapper<SchCalendarDate>()
                        .eq(SchCalendarDate::getSemesterId, semesterId).orderByAsc(SchCalendarDate::getEventDate))
                .stream().map(d -> new TermDtos.DateView(d.getEventDate(), d.getDateType(),
                        d.getFollowDayOfWeek(), d.getFollowDate(), d.getName())).toList();
        return new TermDtos.CalendarView(weeks, dates);
    }

    @Transactional
    public TermDtos.CalendarView putCalendar(AuthenticatedUser user, long semesterId,
                                             TermDtos.CalendarRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        assertNoPublishedVersion(semesterId, "修改教学日历");
        SchSemesterConfig config = requireConfig(semesterId);
        List<TermDtos.WeekView> weeks = req.weeks() == null ? List.of() : req.weeks();
        List<TermDtos.DateView> dates = req.dates() == null ? List.of() : req.dates();
        for (TermDtos.WeekView w : weeks) {
            if (w.weekNo() > config.getTeachingWeeks()) {
                throw BusinessException.badRequest("特殊教学周 " + w.weekNo() + " 超出学期教学周数 "
                        + config.getTeachingWeeks());
            }
        }
        for (TermDtos.DateView d : dates) {
            if (!Set.of("HOLIDAY", "SUSPENDED", "WORKDAY", "MAKEUP").contains(d.dateType())) {
                throw BusinessException.badRequest("日期类型必须是 HOLIDAY/SUSPENDED/WORKDAY/MAKEUP");
            }
            if ("WORKDAY".equals(d.dateType()) && d.followDayOfWeek() == null) {
                throw BusinessException.badRequest("调休上课日 " + d.eventDate() + " 必须指定按星期几上课");
            }
            if ("MAKEUP".equals(d.dateType()) && d.followDate() == null) {
                throw BusinessException.badRequest("补课日 " + d.eventDate() + " 必须指定补哪一天的课");
            }
        }
        weekMapper.delete(new LambdaQueryWrapper<SchCalendarWeek>().eq(SchCalendarWeek::getSemesterId, semesterId));
        for (TermDtos.WeekView w : weeks) {
            SchCalendarWeek row = new SchCalendarWeek();
            row.setSemesterId(semesterId);
            row.setWeekNo(w.weekNo());
            row.setWeekType(w.weekType());
            row.setTeachingAllowed(Boolean.TRUE.equals(w.teachingAllowed()));
            row.setNote(w.note());
            weekMapper.insert(row);
        }
        dateMapper.delete(new LambdaQueryWrapper<SchCalendarDate>().eq(SchCalendarDate::getSemesterId, semesterId));
        for (TermDtos.DateView d : dates) {
            SchCalendarDate row = new SchCalendarDate();
            row.setSemesterId(semesterId);
            row.setEventDate(d.eventDate());
            row.setDateType(d.dateType());
            row.setFollowDayOfWeek(d.followDayOfWeek());
            row.setFollowDate(d.followDate());
            row.setName(d.name());
            row.setCreatedBy(user.userId());
            row.setUpdatedBy(user.userId());
            dateMapper.insert(row);
        }
        audit(user, "SCHEDULING_CALENDAR_REPLACE", semesterId,
                Map.of("weeks", weeks.size(), "dates", dates.size()));
        return calendar(user, semesterId);
    }

    // ------------------------------------------------------------------ 约束权重

    public List<TermDtos.ConstraintView> constraints(AuthenticatedUser user, long semesterId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        requireSemester(semesterId);
        return constraintViews(semesterId);
    }

    @Transactional
    public List<TermDtos.ConstraintView> putConstraints(AuthenticatedUser user, long semesterId,
                                                        TermDtos.ConstraintRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        requireSemester(semesterId);
        if (req.items() != null) {
            for (TermDtos.ConstraintItem item : req.items()) {
                ConstraintCode code;
                try {
                    code = ConstraintCode.valueOf(item.code());
                } catch (IllegalArgumentException ex) {
                    throw BusinessException.badRequest("未知约束代码 " + item.code());
                }
                if (code.hard()) {
                    throw BusinessException.badRequest("硬约束 " + code.label() + " 不可配置权重或关闭");
                }
                SchConstraintConfig row = constraintMapper.selectOne(new LambdaQueryWrapper<SchConstraintConfig>()
                        .eq(SchConstraintConfig::getSemesterId, semesterId)
                        .eq(SchConstraintConfig::getCode, code.name()));
                if (row == null) {
                    row = new SchConstraintConfig();
                    row.setSemesterId(semesterId);
                    row.setCode(code.name());
                    row.setEnabled(item.enabled());
                    row.setWeight(item.weight());
                    row.setUpdatedBy(user.userId());
                    constraintMapper.insert(row);
                } else {
                    row.setEnabled(item.enabled());
                    row.setWeight(item.weight());
                    row.setUpdatedBy(user.userId());
                    constraintMapper.update(null, new LambdaUpdateWrapper<SchConstraintConfig>()
                            .eq(SchConstraintConfig::getSemesterId, semesterId)
                            .eq(SchConstraintConfig::getCode, code.name())
                            .set(SchConstraintConfig::getEnabled, item.enabled())
                            .set(SchConstraintConfig::getWeight, item.weight())
                            .set(SchConstraintConfig::getUpdatedBy, user.userId())
                            .set(SchConstraintConfig::getUpdatedAt, LocalDateTime.now(clock)));
                }
            }
        }
        audit(user, "SCHEDULING_CONSTRAINT_UPDATE", semesterId, Map.of("items",
                req.items() == null ? 0 : req.items().size()));
        return constraintViews(semesterId);
    }

    /** 学期实际生效的软约束权重（未配置的取默认值，enabled=false 视为 0）。 */
    public Map<ConstraintCode, Integer> effectiveWeights(long semesterId) {
        Map<ConstraintCode, Integer> weights = new java.util.EnumMap<>(ConstraintCode.class);
        for (ConstraintCode code : ConstraintCode.values()) {
            if (!code.hard()) {
                weights.put(code, code.defaultWeight());
            }
        }
        for (SchConstraintConfig row : constraintMapper.selectList(new LambdaQueryWrapper<SchConstraintConfig>()
                .eq(SchConstraintConfig::getSemesterId, semesterId))) {
            try {
                ConstraintCode code = ConstraintCode.valueOf(row.getCode());
                if (!code.hard()) {
                    weights.put(code, Boolean.FALSE.equals(row.getEnabled()) ? 0 : row.getWeight());
                }
            } catch (IllegalArgumentException ignored) {
                // 历史遗留的未知代码忽略
            }
        }
        return weights;
    }

    private List<TermDtos.ConstraintView> constraintViews(long semesterId) {
        Map<ConstraintCode, Integer> weights = effectiveWeights(semesterId);
        List<TermDtos.ConstraintView> out = new ArrayList<>();
        for (ConstraintCode code : ConstraintCode.values()) {
            int weight = weights.getOrDefault(code, code.defaultWeight());
            out.add(new TermDtos.ConstraintView(code.name(), code.label(), code.hard(), !code.hard() && weight > 0,
                    weight, code.defaultWeight()));
        }
        return out;
    }

    // ------------------------------------------------------------------ 共享

    /**
     * 已发布课表的学期不允许改“时间语义”（教学周数、作息、教学日历）：
     * 这些改动会让已发布课表对应的真实日期整体错位，必须先撤销发布。
     */
    private void assertNoPublishedVersion(long semesterId, String action) {
        Long published = versionMapper.selectCount(new LambdaQueryWrapper<SchVersion>()
                .eq(SchVersion::getSemesterId, semesterId)
                .eq(SchVersion::getStatus, VersionStatus.PUBLISHED));
        if (published != null && published > 0) {
            throw BusinessException.conflict("该学期已有发布中的课表，不能" + action
                    + "；请先撤销发布（POST /api/v1/scheduling/versions/{id}/unpublish）");
        }
    }

    public Semester requireSemester(long semesterId) {
        Semester semester = semesterMapper.selectById(semesterId);
        if (semester == null) {
            throw BusinessException.notFound("学期");
        }
        return semester;
    }

    public SchSemesterConfig requireConfig(long semesterId) {
        SchSemesterConfig config = configMapper.selectById(semesterId);
        if (config == null) {
            throw BusinessException.rule("学期尚未配置排课参数，请先调用 PUT /api/v1/scheduling/terms/"
                    + semesterId + "/config 配置教学周数、第 1 周周一、教学日与每日节数");
        }
        return config;
    }

    public List<SchPeriod> periodRows(long semesterId) {
        return periodMapper.selectList(new LambdaQueryWrapper<SchPeriod>()
                .eq(SchPeriod::getSemesterId, semesterId).orderByAsc(SchPeriod::getPeriodNo));
    }

    public List<SchCalendarWeek> weekRows(long semesterId) {
        return weekMapper.selectList(new LambdaQueryWrapper<SchCalendarWeek>()
                .eq(SchCalendarWeek::getSemesterId, semesterId).orderByAsc(SchCalendarWeek::getWeekNo));
    }

    public List<SchCalendarDate> dateRows(long semesterId) {
        return dateMapper.selectList(new LambdaQueryWrapper<SchCalendarDate>()
                .eq(SchCalendarDate::getSemesterId, semesterId).orderByAsc(SchCalendarDate::getEventDate));
    }

    /** 教学日：配置中的星期集合，升序。 */
    public static List<Integer> parseDays(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of(1, 2, 3, 4, 5);
        }
        Set<Integer> set = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            int day = Integer.parseInt(t);
            if (day >= 1 && day <= 7) {
                set.add(day);
            }
        }
        return set.stream().sorted().toList();
    }

    public static String joinDays(List<Integer> days) {
        return days.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** [startWeek, endWeek] 内符合单双周的周次位图。 */
    public static long weekMask(int startWeek, int endWeek, String parity) {
        WeekParity p;
        try {
            p = parity == null ? WeekParity.ALL : WeekParity.valueOf(parity);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest("单双周必须是 ALL/ODD/EVEN");
        }
        return Weeks.range(startWeek, endWeek, p);
    }

    /** 计算某学期第 w 周的周一。 */
    public static LocalDate mondayOf(SchSemesterConfig config, int week) {
        return config.getFirstWeekStart().plusWeeks(week - 1L);
    }

    /** 日期落在第几教学周（不在学期内返回 0）。 */
    public static int weekOf(SchSemesterConfig config, LocalDate date) {
        if (date == null || date.isBefore(config.getFirstWeekStart())) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(config.getFirstWeekStart(), date);
        int week = (int) (days / 7) + 1;
        return week <= config.getTeachingWeeks() ? week : 0;
    }

    private void validateFirstWeek(LocalDate firstWeekStart, int teachingWeeks) {
        if (firstWeekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw BusinessException.badRequest("第 1 周的开始日期必须是周一（当前为 "
                    + firstWeekStart.getDayOfWeek() + "）");
        }
        if (teachingWeeks < 1 || teachingWeeks > Weeks.MAX_WEEKS) {
            throw BusinessException.badRequest("教学周数必须在 1~30 之间");
        }
    }

    private TermDtos.TermView view(Semester s, SchSemesterConfig config) {
        Integer periodCount = periodMapper.selectCount(new LambdaQueryWrapper<SchPeriod>()
                .eq(SchPeriod::getSemesterId, s.getId())).intValue();
        TermDtos.ConfigView cv = config == null ? null : new TermDtos.ConfigView(config.getTeachingWeeks(),
                config.getFirstWeekStart(), parseDays(config.getTeachingDays()), config.getPeriodsPerDay(),
                config.getDefaultTravelMinutes(), config.getMaxRoomCandidates(), periodCount, periodCount > 0);
        return new TermDtos.TermView(s.getId(), s.getCode(), s.getName(), s.getStartDate(), s.getEndDate(),
                s.getStatus() == null ? null : s.getStatus().name(), cv);
    }

    private TermDtos.ConfigView configView(SchSemesterConfig config) {
        Integer periodCount = periodMapper.selectCount(new LambdaQueryWrapper<SchPeriod>()
                .eq(SchPeriod::getSemesterId, config.getSemesterId())).intValue();
        return new TermDtos.ConfigView(config.getTeachingWeeks(), config.getFirstWeekStart(),
                parseDays(config.getTeachingDays()), config.getPeriodsPerDay(), config.getDefaultTravelMinutes(),
                config.getMaxRoomCandidates(), periodCount, periodCount > 0);
    }

    private void audit(AuthenticatedUser user, String action, Long targetId, Map<String, ?> detail) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("semester", targetId);
        detail.forEach(b::detail);
        auditService.record(b.build());
    }
}
