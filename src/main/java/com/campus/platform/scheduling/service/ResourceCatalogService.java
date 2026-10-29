package com.campus.platform.scheduling.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.course.entity.Semester;
import com.campus.platform.course.mapper.SemesterMapper;
import com.campus.platform.scheduling.dto.CatalogDtos;
import com.campus.platform.scheduling.entity.SchAssignment;
import com.campus.platform.scheduling.entity.SchCampus;
import com.campus.platform.scheduling.entity.SchCampusTravel;
import com.campus.platform.scheduling.entity.SchClassProfile;
import com.campus.platform.scheduling.entity.SchRoom;
import com.campus.platform.scheduling.entity.SchTask;
import com.campus.platform.scheduling.entity.SchTaskTeacher;
import com.campus.platform.scheduling.entity.SchTeacherCourse;
import com.campus.platform.scheduling.entity.SchTeacherProfile;
import com.campus.platform.scheduling.entity.SchVersion;
import com.campus.platform.scheduling.entity.VersionStatus;
import com.campus.platform.scheduling.mapper.SchAssignmentMapper;
import com.campus.platform.scheduling.mapper.SchCampusMapper;
import com.campus.platform.scheduling.mapper.SchCampusTravelMapper;
import com.campus.platform.scheduling.mapper.SchClassProfileMapper;
import com.campus.platform.scheduling.mapper.SchRoomMapper;
import com.campus.platform.scheduling.mapper.SchTaskMapper;
import com.campus.platform.scheduling.mapper.SchTaskTeacherMapper;
import com.campus.platform.scheduling.mapper.SchTeacherCourseMapper;
import com.campus.platform.scheduling.mapper.SchTeacherProfileMapper;
import com.campus.platform.scheduling.mapper.SchVersionMapper;
import com.campus.platform.scheduling.solver.model.RuleTarget;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.user.entity.OrgType;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.mapper.SysOrgUnitMapper;
import com.campus.platform.scheduling.mapper.SchedulingQueryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 排课基础数据：校区、教室、教师排课档案、行政班排课档案，以及教室/教师/班级的可用时间。
 * <p>
 * 教师与行政班复用 {@code sys_user} / {@code sys_org_unit}，只维护排课口径的扩展档案；
 * 可用时间统一存放在 {@code sch_time_rule}（ROOM/TEACHER/CLASS 的 FORBIDDEN/AVOID/PREFERRED）。
 */
@Service
public class ResourceCatalogService {

    static final String MODULE = "scheduling";
    private static final Set<String> ROOM_TYPES = Set.of("NORMAL", "MULTIMEDIA", "COMPUTER_LAB", "LANGUAGE_LAB",
            "LAB", "GYM", "OTHER");

    private final SchCampusMapper campusMapper;
    private final SchCampusTravelMapper travelMapper;
    private final SchRoomMapper roomMapper;
    private final SchTeacherProfileMapper teacherProfileMapper;
    private final SchTeacherCourseMapper teacherCourseMapper;
    private final SchClassProfileMapper classProfileMapper;
    private final SysOrgUnitMapper orgUnitMapper;
    private final SchedulingQueryMapper queryMapper;
    private final SchVersionMapper versionMapper;
    private final SchAssignmentMapper assignmentMapper;
    private final SchTaskMapper taskMapper;
    private final SchTaskTeacherMapper taskTeacherMapper;
    private final RuleService ruleService;
    private final SemesterMapper semesterMapper;
    private final AuditService auditService;

    public ResourceCatalogService(SchCampusMapper campusMapper, SchCampusTravelMapper travelMapper,
                                  SchRoomMapper roomMapper, SchTeacherProfileMapper teacherProfileMapper,
                                  SchTeacherCourseMapper teacherCourseMapper, SchClassProfileMapper classProfileMapper,
                                  SysOrgUnitMapper orgUnitMapper, SchedulingQueryMapper queryMapper,
                                  SchVersionMapper versionMapper, SchAssignmentMapper assignmentMapper,
                                  SchTaskMapper taskMapper, SchTaskTeacherMapper taskTeacherMapper,
                                  RuleService ruleService, SemesterMapper semesterMapper,
                                  AuditService auditService) {
        this.campusMapper = campusMapper;
        this.travelMapper = travelMapper;
        this.roomMapper = roomMapper;
        this.teacherProfileMapper = teacherProfileMapper;
        this.teacherCourseMapper = teacherCourseMapper;
        this.classProfileMapper = classProfileMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.queryMapper = queryMapper;
        this.versionMapper = versionMapper;
        this.assignmentMapper = assignmentMapper;
        this.taskMapper = taskMapper;
        this.taskTeacherMapper = taskTeacherMapper;
        this.ruleService = ruleService;
        this.semesterMapper = semesterMapper;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ 校区

    public List<CatalogDtos.CampusView> listCampuses(AuthenticatedUser user) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        return campusMapper.selectList(new LambdaQueryWrapper<SchCampus>().orderByAsc(SchCampus::getCode))
                .stream().map(c -> new CatalogDtos.CampusView(c.getId(), c.getCode(), c.getName(), c.getStatus()))
                .toList();
    }

    @Transactional
    public CatalogDtos.CampusView createCampus(AuthenticatedUser user, CatalogDtos.CampusRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        if (campusMapper.selectCount(new LambdaQueryWrapper<SchCampus>().eq(SchCampus::getCode, req.code())) > 0) {
            throw BusinessException.conflict("校区编码已存在");
        }
        SchCampus row = new SchCampus();
        row.setCode(req.code().trim());
        row.setName(req.name().trim());
        row.setStatus(req.status() == null ? "ACTIVE" : req.status());
        row.setCreatedBy(user.userId());
        row.setUpdatedBy(user.userId());
        campusMapper.insert(row);
        audit(user, "SCHEDULING_CAMPUS_CREATE", row.getId(), Map.of("code", row.getCode()));
        return new CatalogDtos.CampusView(row.getId(), row.getCode(), row.getName(), row.getStatus());
    }

    @Transactional
    public CatalogDtos.CampusView updateCampus(AuthenticatedUser user, long id, CatalogDtos.CampusRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        SchCampus row = campusMapper.selectById(id);
        if (row == null) {
            throw BusinessException.notFound("校区");
        }
        row.setName(req.name().trim());
        if (req.status() != null) {
            row.setStatus(req.status());
        }
        row.setUpdatedBy(user.userId());
        campusMapper.updateById(row);
        audit(user, "SCHEDULING_CAMPUS_UPDATE", id, Map.of("code", row.getCode()));
        return new CatalogDtos.CampusView(row.getId(), row.getCode(), row.getName(), row.getStatus());
    }

    public List<CatalogDtos.TravelView> listTravel(AuthenticatedUser user) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        return travelMapper.selectList(null).stream()
                .map(t -> new CatalogDtos.TravelView(t.getFromCampusId(), t.getToCampusId(), t.getMinutes()))
                .toList();
    }

    @Transactional
    public List<CatalogDtos.TravelView> putTravel(AuthenticatedUser user, CatalogDtos.TravelRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        for (CatalogDtos.TravelItem item : req.items()) {
            if (Objects.equals(item.fromCampusId(), item.toCampusId())) {
                throw BusinessException.badRequest("校区通行时间的两个校区不能相同");
            }
            long lo = Math.min(item.fromCampusId(), item.toCampusId());
            long hi = Math.max(item.fromCampusId(), item.toCampusId());
            if (campusMapper.selectById(lo) == null || campusMapper.selectById(hi) == null) {
                throw BusinessException.badRequest("校区不存在：" + lo + " / " + hi);
            }
            int updated = travelMapper.update(null, new LambdaUpdateWrapper<SchCampusTravel>()
                    .eq(SchCampusTravel::getFromCampusId, lo)
                    .eq(SchCampusTravel::getToCampusId, hi)
                    .set(SchCampusTravel::getMinutes, item.minutes()));
            if (updated == 0) {
                SchCampusTravel row = new SchCampusTravel();
                row.setFromCampusId(lo);
                row.setToCampusId(hi);
                row.setMinutes(item.minutes());
                travelMapper.insert(row);
            }
        }
        audit(user, "SCHEDULING_TRAVEL_UPDATE", null, Map.of("items", req.items().size()));
        return listTravel(user);
    }

    // ------------------------------------------------------------------ 教室

    public PageResult<CatalogDtos.RoomView> listRooms(AuthenticatedUser user, CatalogDtos.RoomQuery query) {
        // 教师需要教室目录来「按教室查课」，学生两个权限点都没有
        Permissions.requireAny(user, Permissions.SCHEDULE_READ, Permissions.SCHEDULE_PREFERENCE_SELF);
        Page<SchRoom> page = roomMapper.selectPage(query.toPage(), new LambdaQueryWrapper<SchRoom>()
                .eq(query.getCampusId() != null, SchRoom::getCampusId, query.getCampusId())
                .eq(query.getRoomType() != null, SchRoom::getRoomType, query.getRoomType())
                .eq(query.getStatus() != null, SchRoom::getStatus, query.getStatus())
                .and(query.getKeyword() != null && !query.getKeyword().isBlank(),
                        w -> w.like(SchRoom::getCode, query.getKeyword())
                                .or().like(SchRoom::getName, query.getKeyword()))
                .orderByAsc(SchRoom::getCode));
        Map<Long, String> campuses = campusNames();
        return PageResult.of(page, r -> view(r, campuses));
    }

    public CatalogDtos.RoomView getRoom(AuthenticatedUser user, long id) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        SchRoom room = roomMapper.selectById(id);
        if (room == null) {
            throw BusinessException.notFound("教室");
        }
        return view(room, campusNames());
    }

    @Transactional
    public CatalogDtos.RoomView createRoom(AuthenticatedUser user, CatalogDtos.RoomRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        validateRoom(req);
        if (roomMapper.selectCount(new LambdaQueryWrapper<SchRoom>().eq(SchRoom::getCode, req.code())) > 0) {
            throw BusinessException.conflict("教室编号 " + req.code() + " 已存在");
        }
        SchRoom row = new SchRoom();
        apply(row, req, user);
        row.setVersion(0);
        roomMapper.insert(row);
        audit(user, "SCHEDULING_ROOM_CREATE", row.getId(), Map.of("code", row.getCode(),
                "capacity", row.getCapacity()));
        return view(row, campusNames());
    }

    @Transactional
    public CatalogDtos.RoomView updateRoom(AuthenticatedUser user, long id, CatalogDtos.RoomRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        validateRoom(req);
        SchRoom row = roomMapper.selectById(id);
        if (row == null) {
            throw BusinessException.notFound("教室");
        }
        if (!row.getCode().equals(req.code())
                && roomMapper.selectCount(new LambdaQueryWrapper<SchRoom>().eq(SchRoom::getCode, req.code())) > 0) {
            throw BusinessException.conflict("教室编号 " + req.code() + " 已存在");
        }
        apply(row, req, user);
        if (req.version() != null && !req.version().equals(row.getVersion())) {
            throw BusinessException.conflict("教室已被他人修改，请刷新后重试");
        }
        // 乐观锁由 @Version 处理，不能手工递增
        roomMapper.updateById(row);
        audit(user, "SCHEDULING_ROOM_UPDATE", id, Map.of("code", row.getCode()));
        return view(row, campusNames());
    }

    public List<CatalogDtos.TimeRuleView> roomAvailability(AuthenticatedUser user, long roomId, long semesterId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        return ruleService.forTarget(semesterId, RuleTarget.ROOM, roomId);
    }

    @Transactional
    public List<CatalogDtos.TimeRuleView> putRoomAvailability(AuthenticatedUser user, long roomId, long semesterId,
                                                              List<CatalogDtos.TimeRuleRequest> requests) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        if (roomMapper.selectById(roomId) == null) {
            throw BusinessException.notFound("教室");
        }
        return ruleService.replaceForTarget(user, semesterId, RuleTarget.ROOM, roomId,
                normalize(requests, "ROOM", roomId));
    }

    // ------------------------------------------------------------------ 教师

    public List<CatalogDtos.TeacherView> listTeachers(AuthenticatedUser user, String keyword) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        List<SysUser> teachers = queryMapper.selectActiveTeachers();
        Map<Long, SchTeacherProfile> profiles = teacherProfileMapper.selectList(null).stream()
                .collect(Collectors.toMap(SchTeacherProfile::getTeacherId, p -> p, (a, b) -> a));
        Map<Long, List<Long>> courses = teacherCourses();
        Map<Long, String> orgNames = orgUnitMapper.selectList(null).stream()
                .collect(Collectors.toMap(SysOrgUnit::getId, SysOrgUnit::getName, (a, b) -> a));
        String kw = keyword == null ? null : keyword.trim().toLowerCase();
        return teachers.stream()
                .filter(t -> kw == null || kw.isEmpty() || t.getDisplayName().toLowerCase().contains(kw)
                        || t.getUsername().toLowerCase().contains(kw))
                .map(t -> {
                    SchTeacherProfile p = profiles.get(t.getId());
                    return new CatalogDtos.TeacherView(t.getId(), t.getUsername(), t.getDisplayName(),
                            t.getOrgUnitId(), orgNames.get(t.getOrgUnitId()),
                            p == null ? null : p.getTitle(), p == null ? null : p.getHomeCampusId(),
                            p == null ? null : p.getMaxWeeklyPeriods(), p == null ? null : p.getMaxDailyPeriods(),
                            p == null ? null : p.getMaxDailySessions(),
                            p == null ? null : p.getMaxConsecutivePeriods(),
                            courses.getOrDefault(t.getId(), List.of()));
                }).toList();
    }

    @Transactional
    public CatalogDtos.TeacherView putTeacherProfile(AuthenticatedUser user, long teacherId,
                                                     CatalogDtos.TeacherProfileRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        SysUser teacher = queryMapper.selectActiveTeachers().stream()
                .filter(t -> t.getId() == teacherId).findFirst()
                .orElseThrow(() -> BusinessException.badRequest("教师不存在或未启用：" + teacherId));
        SchTeacherProfile profile = teacherProfileMapper.selectById(teacherId);
        boolean created = false;
        if (profile == null) {
            profile = new SchTeacherProfile();
            profile.setTeacherId(teacherId);
            profile.setVersion(0);
            created = true;
        }
        profile.setOrgUnitId(req.orgUnitId());
        profile.setTitle(req.title());
        profile.setHomeCampusId(req.homeCampusId());
        profile.setMaxWeeklyPeriods(req.maxWeeklyPeriods());
        profile.setMaxDailyPeriods(req.maxDailyPeriods());
        profile.setMaxDailySessions(req.maxDailySessions());
        profile.setMaxConsecutivePeriods(req.maxConsecutivePeriods());
        profile.setNote(req.note());
        profile.setUpdatedBy(user.userId());
        if (created) {
            profile.setCreatedBy(user.userId());
            teacherProfileMapper.insert(profile);
        } else {
            teacherProfileMapper.updateById(profile);
        }
        if (req.courseIds() != null) {
            teacherCourseMapper.delete(new LambdaQueryWrapper<SchTeacherCourse>()
                    .eq(SchTeacherCourse::getTeacherId, teacherId));
            for (Long courseId : new LinkedHashSet<>(req.courseIds())) {
                SchTeacherCourse row = new SchTeacherCourse();
                row.setTeacherId(teacherId);
                row.setCourseId(courseId);
                teacherCourseMapper.insert(row);
            }
        }
        audit(user, "SCHEDULING_TEACHER_PROFILE", teacherId, Map.of("displayName", teacher.getDisplayName()));
        String orgName = teacher.getOrgUnitId() == null ? null
                : java.util.Optional.ofNullable(orgUnitMapper.selectById(teacher.getOrgUnitId()))
                .map(SysOrgUnit::getName).orElse(null);
        return new CatalogDtos.TeacherView(teacher.getId(), teacher.getUsername(), teacher.getDisplayName(),
                teacher.getOrgUnitId(), orgName, profile.getTitle(),
                profile.getHomeCampusId(), profile.getMaxWeeklyPeriods(), profile.getMaxDailyPeriods(),
                profile.getMaxDailySessions(), profile.getMaxConsecutivePeriods(),
                teacherCourses().getOrDefault(teacherId, List.of()));
    }

    public List<CatalogDtos.TimeRuleView> teacherAvailability(AuthenticatedUser user, long teacherId,
                                                              long semesterId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        return ruleService.forTarget(semesterId, RuleTarget.TEACHER, teacherId);
    }

    @Transactional
    public List<CatalogDtos.TimeRuleView> putTeacherAvailability(AuthenticatedUser user, long teacherId,
                                                                 long semesterId,
                                                                 List<CatalogDtos.TimeRuleRequest> requests) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        return ruleService.replaceForTarget(user, semesterId, RuleTarget.TEACHER, teacherId,
                normalize(requests, "TEACHER", teacherId));
    }

    /** 教师工作量：按已发布版本统计（每版本每周节数）。 */
    public CatalogDtos.TeacherWorkloadView workload(AuthenticatedUser user, long teacherId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        SysUser teacher = queryMapper.selectActiveTeachers().stream()
                .filter(t -> t.getId() == teacherId).findFirst()
                .orElseThrow(() -> BusinessException.notFound("教师"));
        List<Long> taskIds = taskTeacherMapper.selectList(new LambdaQueryWrapper<SchTaskTeacher>()
                        .eq(SchTaskTeacher::getTeacherId, teacherId)).stream()
                .map(SchTaskTeacher::getTaskId).toList();
        List<CatalogDtos.WorkloadItem> items = new ArrayList<>();
        int totalSessions = 0;
        int totalPeriods = 0;
        Set<Integer> days = new LinkedHashSet<>();
        if (!taskIds.isEmpty()) {
            Map<Long, SchTask> tasks = taskMapper.selectBatchIds(taskIds).stream()
                    .collect(Collectors.toMap(SchTask::getId, t -> t, (a, b) -> a));
            List<SchVersion> versions = versionMapper.selectList(new LambdaQueryWrapper<SchVersion>()
                    .eq(SchVersion::getStatus, VersionStatus.PUBLISHED));
            Map<Long, String> semesterNames = new LinkedHashMap<>();
            for (SchVersion version : versions) {
                List<SchAssignment> entries = assignmentMapper.selectList(new LambdaQueryWrapper<SchAssignment>()
                        .eq(SchAssignment::getVersionId, version.getId())
                        .in(SchAssignment::getTaskId, taskIds)
                        .isNotNull(SchAssignment::getDayOfWeek));
                if (entries.isEmpty()) {
                    continue;
                }
                int sessions = entries.size();
                int periods = entries.stream().mapToInt(e -> {
                    SchTask t = tasks.get(e.getTaskId());
                    return t == null ? 1 : Math.max(1, t.getPeriodsPerSession());
                }).sum();
                entries.forEach(e -> days.add(e.getDayOfWeek()));
                totalSessions += sessions;
                totalPeriods += periods;
                semesterNames.computeIfAbsent(version.getSemesterId(), this::semesterName);
                items.add(new CatalogDtos.WorkloadItem(version.getId(), version.getSemesterId(),
                        semesterNames.get(version.getSemesterId()), sessions, periods));
            }
        }
        return new CatalogDtos.TeacherWorkloadView(teacherId, teacher.getDisplayName(), items.size(),
                totalSessions, totalPeriods, days.size(), items);
    }

    // ------------------------------------------------------------------ 行政班

    public List<CatalogDtos.ClassView> listClasses(AuthenticatedUser user, String keyword) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        List<SysOrgUnit> classes = orgUnitMapper.selectList(new LambdaQueryWrapper<SysOrgUnit>()
                .eq(SysOrgUnit::getType, OrgType.CLASS)
                .orderByAsc(SysOrgUnit::getCode));
        Map<Long, SchClassProfile> profiles = classProfileMapper.selectList(null).stream()
                .collect(Collectors.toMap(SchClassProfile::getOrgUnitId, p -> p, (a, b) -> a));
        String kw = keyword == null ? null : keyword.trim().toLowerCase();
        return classes.stream()
                .filter(c -> kw == null || kw.isEmpty() || c.getName().toLowerCase().contains(kw)
                        || c.getCode().toLowerCase().contains(kw))
                .map(c -> {
                    SchClassProfile p = profiles.get(c.getId());
                    return new CatalogDtos.ClassView(c.getId(), c.getCode(), c.getName(), c.getParentId(),
                            p == null ? null : p.getGrade(), p == null ? null : p.getStudentCount(),
                            p == null ? null : p.getHomeCampusId(), p == null ? null : p.getMaxDailyPeriods(),
                            p == null ? null : p.getMaxConsecutivePeriods());
                }).toList();
    }

    @Transactional
    public CatalogDtos.ClassView putClassProfile(AuthenticatedUser user, long classId,
                                                 CatalogDtos.ClassProfileRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        SysOrgUnit org = orgUnitMapper.selectById(classId);
        if (org == null || org.getType() != OrgType.CLASS) {
            throw BusinessException.badRequest("行政班不存在：" + classId);
        }
        SchClassProfile profile = classProfileMapper.selectById(classId);
        boolean created = false;
        if (profile == null) {
            profile = new SchClassProfile();
            profile.setOrgUnitId(classId);
            profile.setVersion(0);
            created = true;
        }
        profile.setStudentCount(req.studentCount());
        profile.setGrade(req.grade());
        profile.setHomeCampusId(req.homeCampusId());
        profile.setMaxDailyPeriods(req.maxDailyPeriods());
        profile.setMaxConsecutivePeriods(req.maxConsecutivePeriods());
        profile.setUpdatedBy(user.userId());
        if (created) {
            profile.setCreatedBy(user.userId());
            classProfileMapper.insert(profile);
        } else {
            classProfileMapper.updateById(profile);
        }
        audit(user, "SCHEDULING_CLASS_PROFILE", classId, Map.of("students", req.studentCount()));
        return new CatalogDtos.ClassView(org.getId(), org.getCode(), org.getName(), org.getParentId(),
                profile.getGrade(), profile.getStudentCount(), profile.getHomeCampusId(),
                profile.getMaxDailyPeriods(), profile.getMaxConsecutivePeriods());
    }

    public List<CatalogDtos.TimeRuleView> classAvailability(AuthenticatedUser user, long classId, long semesterId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        return ruleService.forTarget(semesterId, RuleTarget.CLASS, classId);
    }

    @Transactional
    public List<CatalogDtos.TimeRuleView> putClassAvailability(AuthenticatedUser user, long classId, long semesterId,
                                                               List<CatalogDtos.TimeRuleRequest> requests) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        return ruleService.replaceForTarget(user, semesterId, RuleTarget.CLASS, classId,
                normalize(requests, "CLASS", classId));
    }

    // ------------------------------------------------------------------ helpers

    private List<CatalogDtos.TimeRuleRequest> normalize(List<CatalogDtos.TimeRuleRequest> requests, String type,
                                                        long targetId) {
        if (requests == null) {
            return List.of();
        }
        return requests.stream().map(r -> new CatalogDtos.TimeRuleRequest(r.semesterId(), type, targetId,
                r.kind(), r.dayOfWeek(), r.startPeriod(), r.endPeriod(), r.startWeek(), r.endWeek(), r.weeks(),
                r.weight(), r.note())).toList();
    }

    private void validateRoom(CatalogDtos.RoomRequest req) {
        if (campusMapper.selectById(req.campusId()) == null) {
            throw BusinessException.badRequest("校区不存在：" + req.campusId());
        }
        if (!ROOM_TYPES.contains(req.roomType())) {
            throw BusinessException.badRequest("教室类型必须是 " + ROOM_TYPES);
        }
    }

    private void apply(SchRoom row, CatalogDtos.RoomRequest req, AuthenticatedUser user) {
        row.setCode(req.code().trim());
        row.setName(req.name().trim());
        row.setCampusId(req.campusId());
        row.setBuilding(req.building());
        row.setFloorNo(req.floorNo());
        row.setCapacity(req.capacity());
        row.setRoomType(req.roomType());
        row.setFeatures(req.features() == null ? null : String.join(",", req.features()));
        row.setStatus(req.status() == null ? "ACTIVE" : req.status());
        row.setNote(req.note());
        row.setUpdatedBy(user.userId());
        if (row.getCreatedBy() == null) {
            row.setCreatedBy(user.userId());
        }
    }

    private CatalogDtos.RoomView view(SchRoom r, Map<Long, String> campuses) {
        return new CatalogDtos.RoomView(r.getId(), r.getCode(), r.getName(), r.getCampusId(),
                campuses.get(r.getCampusId()), r.getBuilding(), r.getFloorNo(), r.getCapacity(), r.getRoomType(),
                split(r.getFeatures()), r.getStatus(), r.getNote(), r.getVersion());
    }

    private Map<Long, String> campusNames() {
        return campusMapper.selectList(null).stream()
                .collect(Collectors.toMap(SchCampus::getId, SchCampus::getName, (a, b) -> a));
    }

    private Map<Long, List<Long>> teacherCourses() {
        Map<Long, List<Long>> m = new LinkedHashMap<>();
        teacherCourseMapper.selectList(null).forEach(r ->
                m.computeIfAbsent(r.getTeacherId(), k -> new ArrayList<>()).add(r.getCourseId()));
        return m;
    }

    private String semesterName(long semesterId) {
        Semester semester = semesterMapper.selectById(semesterId);
        return semester == null ? String.valueOf(semesterId) : semester.getName();
    }

    static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    static List<String> sortedNames(List<String> names) {
        return names.stream().filter(Objects::nonNull).sorted(Comparator.naturalOrder()).toList();
    }

    private void audit(AuthenticatedUser user, String action, Long targetId, Map<String, ?> detail) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("scheduling_resource", targetId);
        detail.forEach(b::detail);
        auditService.record(b.build());
    }
}
