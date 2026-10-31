package com.campus.platform.timetable.service;

import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.course.dto.MyScheduleItem;
import com.campus.platform.course.entity.WeekParity;
import com.campus.platform.course.mapper.SemesterMapper;
import com.campus.platform.course.service.CourseScheduleService;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.timetable.dto.TimetableItem;
import com.campus.platform.timetable.mapper.TimetableMapper;
import com.campus.platform.user.entity.OrgType;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.mapper.SysOrgUnitMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 统一课表。
 * <p>
 * 系统里有两套排课：课程模块的手工排课（{@code course_schedule}）与自动排课模块的求解结果
 * （{@code sch_assignment}）。此前课程页读前者、排课工作台读后者，同一个学生可能看到两份不同的课表。
 * <p>
 * 统一口径：<b>该学期只要有已发布的自动排课结果，就以它为准</b>（那是教务正式排定的课表，
 * 带真实教室、经过冲突校验）；没有才回退到课程模块的手工排课。
 * 两者不会混排，所以不会出现同一门课显示两次。
 */
@Service
public class TimetableService {

    private final TimetableMapper timetableMapper;
    private final SysOrgUnitMapper orgUnitMapper;
    private final SemesterMapper semesterMapper;
    private final CourseScheduleService courseScheduleService;
    /**
     * 课表是典型的「读多写极少」数据：一个学期里只在排课发布时变化一次。
     * 缓存几十秒可以把高并发下的数据库查询压到接近 0，代价是发布后最多延迟一个 TTL 生效。
     * key = userId + ":" + semesterId（semesterId 为 null 时用 all）。
     */
    private final Cache<String, List<TimetableItem>> cache;

    public TimetableService(TimetableMapper timetableMapper, SysOrgUnitMapper orgUnitMapper,
                            SemesterMapper semesterMapper, CourseScheduleService courseScheduleService,
                            @Value("${app.timetable.cache-ttl:30s}") Duration cacheTtl) {
        this.timetableMapper = timetableMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.semesterMapper = semesterMapper;
        this.courseScheduleService = courseScheduleService;
        this.cache = Caffeine.newBuilder()
                .maximumSize(20_000)
                .expireAfterWrite(cacheTtl)
                .build();
    }

    /**
     * 本人课表。
     *
     * @param semesterId 为空表示不限学期
     */
    public List<TimetableItem> mine(AuthenticatedUser user, Long semesterId) {
        Permissions.require(user, Permissions.TIMETABLE_READ);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        String key = user.userId() + ":" + (semesterId == null ? "all" : semesterId);
        return cache.get(key, k -> load(user, semesterId));
    }

    /** 真正计算课表（不走缓存）。 */
    private List<TimetableItem> load(AuthenticatedUser user, Long semesterId) {

        Long teacherId = user.isTeacher() ? user.userId() : null;
        Long classId = resolveClassId(user);

        // 1) 自动排课已发布结果优先
        if (teacherId != null || classId != null) {
            List<TimetableMapper.ScheduledRow> rows =
                    timetableMapper.selectMyAssignments(teacherId, classId, semesterId);
            if (!rows.isEmpty()) {
                Map<Long, String> semesterNames = new HashMap<>();
                Set<Long> semesterIds = rows.stream().map(TimetableMapper.ScheduledRow::semesterId)
                        .filter(Objects::nonNull).collect(Collectors.toSet());
                if (!semesterIds.isEmpty()) {
                    semesterMapper.selectBatchIds(semesterIds)
                            .forEach(s -> semesterNames.put(s.getId(), s.getName()));
                }
                return rows.stream().map(row -> toItem(row, semesterNames)).toList();
            }
        }

        // 2) 回退到课程模块的手工排课
        List<TimetableItem> fallback = new ArrayList<>();
        for (MyScheduleItem item : courseScheduleService.mySchedule(user, semesterId)) {
            fallback.add(new TimetableItem(
                    item.semesterId(),
                    item.semesterName(),
                    item.courseId(),
                    item.courseCode(),
                    item.courseName(),
                    item.teacherNames(),
                    item.scheduleId(),
                    null,
                    item.dayOfWeek(),
                    item.startPeriod(),
                    item.endPeriod(),
                    item.startWeek(),
                    item.endWeek(),
                    item.weekParity(),
                    item.classroom(),
                    TimetableItem.SOURCE_COURSE));
        }
        return fallback;
    }

    /** 学生定位到行政班；教师或未挂在班级下的账号返回 null。 */
    private Long resolveClassId(AuthenticatedUser user) {
        if (user.orgUnitId() == null) {
            return null;
        }
        SysOrgUnit org = orgUnitMapper.selectById(user.orgUnitId());
        return org != null && org.getType() == OrgType.CLASS ? org.getId() : null;
    }

    private TimetableItem toItem(TimetableMapper.ScheduledRow row, Map<Long, String> semesterNames) {
        List<String> teachers = row.teacherNames() == null || row.teacherNames().isBlank()
                ? List.of()
                : Arrays.stream(row.teacherNames().split("、")).filter(s -> !s.isBlank()).toList();
        return new TimetableItem(
                row.semesterId(),
                row.semesterId() == null ? null : semesterNames.get(row.semesterId()),
                row.courseId(),
                row.courseCode(),
                row.courseName(),
                teachers,
                null,
                row.assignmentId(),
                row.dayOfWeek(),
                row.startPeriod(),
                row.endPeriod(),
                row.startWeek(),
                row.endWeek(),
                WeekParity.valueOf(row.weekParity()),
                row.roomName(),
                TimetableItem.SOURCE_SCHEDULING);
    }
}
