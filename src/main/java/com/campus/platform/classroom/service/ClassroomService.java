package com.campus.platform.classroom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.classroom.dto.FreeDaySummary;
import com.campus.platform.classroom.dto.FreeSlotView;
import com.campus.platform.classroom.dto.FreeWeekView;
import com.campus.platform.classroom.dto.PeriodTimeView;
import com.campus.platform.classroom.dto.RoomOccupancyRow;
import com.campus.platform.classroom.dto.RoomView;
import com.campus.platform.classroom.dto.ScheduleOccupancyRow;
import com.campus.platform.classroom.mapper.ClassroomQueryMapper;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.course.entity.Course;
import com.campus.platform.course.entity.CourseSchedule;
import com.campus.platform.course.entity.Semester;
import com.campus.platform.course.entity.SemesterStatus;
import com.campus.platform.course.mapper.CourseMapper;
import com.campus.platform.course.mapper.CourseScheduleMapper;
import com.campus.platform.course.mapper.SemesterMapper;
import com.campus.platform.scheduling.entity.SchPeriod;
import com.campus.platform.scheduling.entity.SchRoom;
import com.campus.platform.scheduling.entity.SchSemesterConfig;
import com.campus.platform.scheduling.mapper.SchPeriodMapper;
import com.campus.platform.scheduling.mapper.SchRoomMapper;
import com.campus.platform.scheduling.mapper.SchSemesterConfigMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 空教室查询：以「已发布排课 + 课程排课」的并集作为占用来源，按日期、节次与周次计算空闲教室。
 * <p>
 * 数据来源有两个：
 * <ol>
 *   <li>{@code sch_assignment}（自动排课发布版本），按 room_id 精确占用；</li>
 *   <li>{@code course_schedule}（课程模块的排课），教室是文本，按编号或名称匹配教室档案。</li>
 * </ol>
 * 两者取并集，避免「排课工作台已排、课程页还没同步」时把有课的教室当成空教室。
 * 没有登记教室档案时，退化为课程排课里出现过的教室名，保证功能可用。
 */
@Service
public class ClassroomService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_PERIOD = 12;

    /** 未配置 sch_period 时的兜底作息，仅用于展示时间，不参与占用计算。 */
    private static final List<PeriodTimeView> DEFAULT_PERIODS = List.of(
            new PeriodTimeView(1, "08:00", "08:45", "MORNING"),
            new PeriodTimeView(2, "08:55", "09:40", "MORNING"),
            new PeriodTimeView(3, "10:00", "10:45", "MORNING"),
            new PeriodTimeView(4, "10:55", "11:40", "MORNING"),
            new PeriodTimeView(5, "14:00", "14:45", "AFTERNOON"),
            new PeriodTimeView(6, "14:55", "15:40", "AFTERNOON"),
            new PeriodTimeView(7, "16:00", "16:45", "AFTERNOON"),
            new PeriodTimeView(8, "16:55", "17:40", "AFTERNOON"),
            new PeriodTimeView(9, "18:30", "19:15", "EVENING"),
            new PeriodTimeView(10, "19:25", "20:10", "EVENING"),
            new PeriodTimeView(11, "20:20", "21:05", "EVENING"),
            new PeriodTimeView(12, "21:15", "22:00", "EVENING"));

    private final SchRoomMapper roomMapper;
    private final SchPeriodMapper periodMapper;
    private final SchSemesterConfigMapper configMapper;
    private final SemesterMapper semesterMapper;
    private final CourseMapper courseMapper;
    private final CourseScheduleMapper scheduleMapper;
    private final ClassroomQueryMapper queryMapper;
    private final Clock clock;

    public ClassroomService(SchRoomMapper roomMapper, SchPeriodMapper periodMapper,
                            SchSemesterConfigMapper configMapper, SemesterMapper semesterMapper,
                            CourseMapper courseMapper, CourseScheduleMapper scheduleMapper,
                            ClassroomQueryMapper queryMapper, Clock clock) {
        this.roomMapper = roomMapper;
        this.periodMapper = periodMapper;
        this.configMapper = configMapper;
        this.semesterMapper = semesterMapper;
        this.courseMapper = courseMapper;
        this.scheduleMapper = scheduleMapper;
        this.queryMapper = queryMapper;
        this.clock = clock;
    }

    /** 某天某节次的空教室明细。 */
    public FreeSlotView free(LocalDate date, Integer startPeriod, Integer endPeriod,
                             Integer minCapacity, String building, String roomType) {
        LocalDate day = date == null ? LocalDate.now(clock) : date;
        Context ctx = loadContext(day, minCapacity, building, roomType);
        int[] range = resolveRange(ctx, startPeriod, endPeriod, day);
        int weekNo = weekOf(ctx, day);
        List<RoomView> freeRooms = freeRooms(ctx, day, weekNo, range[0], range[1]);
        return new FreeSlotView(day, day.getDayOfWeek().getValue(), weekNo,
                isTeachingDay(ctx, day, weekNo), range[0], range[1],
                timeOf(ctx, range[0]), endTimeOf(ctx, range[1]),
                ctx.rooms().size(), freeRooms, ctx.periods(),
                ctx.semester() == null ? null : ctx.semester().getName());
    }

    /** 教室在某天某几节是否空闲（供借用申请复用同一套占用口径）。 */
    public boolean isRoomFree(long roomId, LocalDate date, int startPeriod, int endPeriod) {
        FreeSlotView slot = free(date, startPeriod, endPeriod, null, null, null);
        return slot.rooms().stream().anyMatch(r -> r.id() != null && r.id() == roomId);
    }

    /** 一周（默认今天起 7 天）每天同一时段的空教室数量，用于周视图。 */
    public FreeWeekView week(LocalDate from, Integer startPeriod, Integer endPeriod,
                             Integer minCapacity, String building, String roomType) {
        LocalDate start = from == null ? LocalDate.now(clock) : from;
        Context ctx = loadContext(start, minCapacity, building, roomType);
        int[] range = resolveRange(ctx, startPeriod, endPeriod, start);
        List<FreeDaySummary> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate day = start.plusDays(i);
            int weekNo = weekOf(ctx, day);
            List<RoomView> free = freeRooms(ctx, day, weekNo, range[0], range[1]);
            days.add(new FreeDaySummary(day, day.getDayOfWeek().getValue(), weekNo,
                    isTeachingDay(ctx, day, weekNo), free.size(), ctx.rooms().size()));
        }
        return new FreeWeekView(start, start.plusDays(6), range[0], range[1],
                timeOf(ctx, range[0]), endTimeOf(ctx, range[1]), days);
    }

    /* ---------------- 内部实现 ---------------- */

    private Context loadContext(LocalDate date, Integer minCapacity, String building, String roomType) {
        Semester semester = resolveSemester(date);
        List<RoomView> rooms = loadRooms(minCapacity, building, roomType, semester);
        List<PeriodTimeView> periods = loadPeriods(semester);
        SchSemesterConfig config = semester == null ? null : configMapper.selectById(semester.getId());
        List<RoomOccupancyRow> assignments = semester == null ? List.of()
                : queryMapper.selectPublishedAssignments(semester.getId());
        List<ScheduleOccupancyRow> schedules = semester == null ? List.of()
                : queryMapper.selectCourseSchedules(semester.getId());
        return new Context(semester, config, rooms, periods, assignments, schedules);
    }

    private List<RoomView> loadRooms(Integer minCapacity, String building, String roomType, Semester semester) {
        List<SchRoom> rooms = roomMapper.selectList(new LambdaQueryWrapper<SchRoom>()
                .eq(SchRoom::getStatus, "ACTIVE")
                .ge(minCapacity != null, SchRoom::getCapacity, minCapacity)
                .eq(building != null && !building.isBlank(), SchRoom::getBuilding, building)
                .eq(roomType != null && !roomType.isBlank(), SchRoom::getRoomType, roomType)
                .orderByAsc(SchRoom::getBuilding)
                .orderByAsc(SchRoom::getCode));
        if (!rooms.isEmpty()) {
            return rooms.stream()
                    .map(r -> new RoomView(r.getId(), r.getCode(), r.getName(), r.getBuilding(), r.getFloorNo(),
                            r.getCapacity(), r.getRoomType(), r.getFeatures()))
                    .sorted(Comparator.comparing((RoomView r) -> r.building() == null ? "" : r.building())
                            .thenComparing(r -> r.code() == null ? "" : r.code()))
                    .toList();
        }
        // 没有教室档案时，用课程排课里出现过的教室名兜底（容量/类型/楼栋未知）
        // 一旦按容量、类型或楼栋筛选，就不能用没有这些属性的兜底数据冒充结果
        if (minCapacity != null || (roomType != null && !roomType.isBlank())
                || (building != null && !building.isBlank())) {
            return List.of();
        }
        if (semester == null) {
            return List.of();
        }
        List<Long> courseIds = courseMapper.selectList(new LambdaQueryWrapper<Course>()
                        .select(Course::getId)
                        .eq(Course::getSemesterId, semester.getId())
                        .ne(Course::getStatus, com.campus.platform.course.entity.CourseStatus.CANCELLED))
                .stream().map(Course::getId).toList();
        if (courseIds.isEmpty()) {
            return List.of();
        }
        Map<String, RoomView> byName = new java.util.LinkedHashMap<>();
        for (CourseSchedule schedule : scheduleMapper.selectByCourses(courseIds)) {
            if (schedule.getClassroom() == null || schedule.getClassroom().isBlank()) {
                continue;
            }
            String name = schedule.getClassroom().trim();
            byName.putIfAbsent(normalize(name), new RoomView(null, name, name, null, null, null, "UNKNOWN", null));
        }
        return new ArrayList<>(byName.values());
    }

    private List<PeriodTimeView> loadPeriods(Semester semester) {
        if (semester == null) {
            return DEFAULT_PERIODS;
        }
        List<SchPeriod> rows = periodMapper.selectList(new LambdaQueryWrapper<SchPeriod>()
                .eq(SchPeriod::getSemesterId, semester.getId())
                .orderByAsc(SchPeriod::getPeriodNo));
        if (rows.isEmpty()) {
            return DEFAULT_PERIODS;
        }
        return rows.stream()
                .map(p -> new PeriodTimeView(p.getPeriodNo(), p.getStartTime().format(TIME),
                        p.getEndTime().format(TIME), p.getSection()))
                .toList();
    }

    private Semester resolveSemester(LocalDate date) {
        List<Semester> covering = semesterMapper.selectList(new LambdaQueryWrapper<Semester>()
                .le(Semester::getStartDate, date)
                .ge(Semester::getEndDate, date)
                .orderByDesc(Semester::getStartDate));
        if (!covering.isEmpty()) {
            return covering.stream()
                    .filter(s -> s.getStatus() == SemesterStatus.ACTIVE)
                    .findFirst()
                    .orElse(covering.get(0));
        }
        List<Semester> latest = semesterMapper.selectList(new LambdaQueryWrapper<Semester>()
                .orderByDesc(Semester::getStartDate)
                .last("LIMIT 1"));
        return latest.isEmpty() ? null : latest.get(0);
    }

    private int weekOf(Context ctx, LocalDate date) {
        if (ctx.semester() == null) {
            return 0;
        }
        LocalDate first = ctx.config() != null && ctx.config().getFirstWeekStart() != null
                ? ctx.config().getFirstWeekStart()
                : ctx.semester().getStartDate();
        if (first == null) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(first, date);
        return (int) Math.floorDiv(days, 7) + 1;
    }

    private boolean isTeachingDay(Context ctx, LocalDate date, int weekNo) {
        if (ctx.semester() == null || weekNo < 1) {
            return false;
        }
        if (ctx.config() != null && ctx.config().getTeachingWeeks() != null
                && weekNo > ctx.config().getTeachingWeeks()) {
            return false;
        }
        String days = ctx.config() == null || ctx.config().getTeachingDays() == null
                ? "1,2,3,4,5" : ctx.config().getTeachingDays();
        String target = String.valueOf(date.getDayOfWeek().getValue());
        for (String part : days.split(",")) {
            if (part.trim().equals(target)) {
                return true;
            }
        }
        return false;
    }

    private List<RoomView> freeRooms(Context ctx, LocalDate date, int weekNo, int startPeriod, int endPeriod) {
        int dow = date.getDayOfWeek().getValue();
        Set<Long> busyIds = new HashSet<>();
        Set<String> busyNames = new HashSet<>();
        for (RoomOccupancyRow row : ctx.assignments()) {
            if (matches(dow, weekNo, startPeriod, endPeriod, row.getDayOfWeek(), row.getStartPeriod(),
                    row.getEndPeriod(), row.getStartWeek(), row.getEndWeek(), row.getWeekParity())) {
                if (row.getRoomId() != null) {
                    busyIds.add(row.getRoomId());
                }
                if (row.getRoomName() != null) {
                    busyNames.add(normalize(row.getRoomName()));
                }
                if (row.getRoomCode() != null) {
                    busyNames.add(normalize(row.getRoomCode()));
                }
            }
        }
        for (ScheduleOccupancyRow row : ctx.schedules()) {
            if (matches(dow, weekNo, startPeriod, endPeriod, row.getDayOfWeek(), row.getStartPeriod(),
                    row.getEndPeriod(), row.getStartWeek(), row.getEndWeek(), row.getWeekParity())) {
                busyNames.add(normalize(row.getClassroom()));
            }
        }
        List<RoomView> result = new ArrayList<>();
        for (RoomView room : ctx.rooms()) {
            boolean busy = room.id() != null && busyIds.contains(room.id());
            if (!busy) {
                busy = busyNames.contains(normalize(room.code())) || busyNames.contains(normalize(room.name()));
            }
            if (!busy) {
                result.add(room);
            }
        }
        return result;
    }

    private static boolean matches(int dow, int weekNo, int startPeriod, int endPeriod,
                                   Integer rowDow, Integer rowStart, Integer rowEnd,
                                   Integer startWeek, Integer endWeek, String parity) {
        if (rowDow == null || rowStart == null || rowEnd == null || rowDow != dow) {
            return false;
        }
        if (rowStart > endPeriod || startPeriod > rowEnd) {
            return false;
        }
        if (weekNo < 1) {
            return true; // 假期/学期外：只按星期与节次判断，避免把课表当成空闲
        }
        if (startWeek != null && weekNo < startWeek) {
            return false;
        }
        if (endWeek != null && weekNo > endWeek) {
            return false;
        }
        if ("ODD".equals(parity) && weekNo % 2 == 0) {
            return false;
        }
        if ("EVEN".equals(parity) && weekNo % 2 == 1) {
            return false;
        }
        return true;
    }

    private int[] resolveRange(Context ctx, Integer startPeriod, Integer endPeriod, LocalDate date) {
        if (startPeriod != null && endPeriod != null) {
            if (startPeriod < 1 || endPeriod > MAX_PERIOD || startPeriod > endPeriod) {
                throw BusinessException.badRequest("节次范围应在 1~" + MAX_PERIOD + " 之间，且开始不晚于结束");
            }
            return new int[]{startPeriod, endPeriod};
        }
        if (startPeriod != null && endPeriod == null) {
            return new int[]{startPeriod, Math.min(startPeriod, MAX_PERIOD)};
        }
        if (startPeriod == null && endPeriod != null) {
            return new int[]{Math.max(1, endPeriod), endPeriod};
        }
        int current = currentPeriod(ctx, date);
        return new int[]{current, current};
    }

    private int currentPeriod(Context ctx, LocalDate date) {
        if (!date.equals(LocalDate.now(clock))) {
            return 1;
        }
        LocalTime now = LocalTime.now(clock);
        for (PeriodTimeView p : ctx.periods()) {
            LocalTime start = LocalTime.parse(p.startTime(), TIME);
            LocalTime end = LocalTime.parse(p.endTime(), TIME);
            if (!now.isBefore(start) && !now.isAfter(end)) {
                return p.periodNo();
            }
        }
        // 不在任何一节课内：取下一节，全部结束则取最后一节
        for (PeriodTimeView p : ctx.periods()) {
            if (now.isBefore(LocalTime.parse(p.startTime(), TIME))) {
                return p.periodNo();
            }
        }
        return ctx.periods().isEmpty() ? 1 : ctx.periods().get(ctx.periods().size() - 1).periodNo();
    }

    private String timeOf(Context ctx, int periodNo) {
        return ctx.periods().stream()
                .filter(p -> p.periodNo() == periodNo)
                .map(PeriodTimeView::startTime)
                .findFirst()
                .orElse("—");
    }

    private String endTimeOf(Context ctx, int periodNo) {
        return ctx.periods().stream()
                .filter(p -> p.periodNo() == periodNo)
                .map(PeriodTimeView::endTime)
                .findFirst()
                .orElse("—");
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replace(" ", "");
    }

    private record Context(Semester semester, SchSemesterConfig config, List<RoomView> rooms,
                           List<PeriodTimeView> periods, List<RoomOccupancyRow> assignments,
                           List<ScheduleOccupancyRow> schedules) {
    }
}
