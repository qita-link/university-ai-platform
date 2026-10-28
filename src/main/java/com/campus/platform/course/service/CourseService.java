package com.campus.platform.course.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.course.dto.AvailableCourseView;
import com.campus.platform.course.dto.CourseBlockReason;
import com.campus.platform.course.dto.CourseCreateRequest;
import com.campus.platform.course.dto.CourseDetailView;
import com.campus.platform.course.dto.CourseQuery;
import com.campus.platform.course.dto.CourseStatusRequest;
import com.campus.platform.course.dto.CourseSummaryView;
import com.campus.platform.course.dto.CourseTeacherRequest;
import com.campus.platform.course.dto.CourseTeacherView;
import com.campus.platform.course.dto.CourseUpdateRequest;
import com.campus.platform.course.dto.ScheduleView;
import com.campus.platform.course.entity.Course;
import com.campus.platform.course.entity.CourseSchedule;
import com.campus.platform.course.entity.CourseSelection;
import com.campus.platform.course.entity.CourseStatus;
import com.campus.platform.course.entity.CourseTeacher;
import com.campus.platform.course.entity.SelectionStatus;
import com.campus.platform.course.entity.Semester;
import com.campus.platform.course.entity.TeacherRole;
import com.campus.platform.course.mapper.CourseMapper;
import com.campus.platform.course.mapper.CourseScheduleMapper;
import com.campus.platform.course.mapper.CourseSelectionMapper;
import com.campus.platform.course.mapper.CourseTeacherMapper;
import com.campus.platform.course.mapper.LeaveRequestMapper;
import com.campus.platform.course.mapper.SemesterMapper;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.user.datascope.DataScopeService;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.entity.UserStatus;
import com.campus.platform.user.mapper.SysOrgUnitMapper;
import com.campus.platform.user.mapper.SysUserMapper;
import com.campus.platform.user.service.OrgUnitService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 课程管理：创建、修改、状态机、授课教师、可选课程。
 * <p>
 * 归属规则集中在 {@link CourseAccessService}；本类的公开方法都直接接收 {@link AuthenticatedUser}，
 * 便于阶段七的 AI 工具复用同一套权限判断。
 * 课程资料修改用 {@code version} 乐观锁；选课计数只由 {@link CourseSelectionService} 维护。
 */
@Service
public class CourseService {

    static final String MODULE = "course";

    private final CourseMapper courseMapper;
    private final SemesterMapper semesterMapper;
    private final CourseTeacherMapper teacherMapper;
    private final CourseScheduleMapper scheduleMapper;
    private final CourseSelectionMapper selectionMapper;
    private final LeaveRequestMapper leaveMapper;
    private final SysUserMapper userMapper;
    private final SysOrgUnitMapper orgMapper;
    private final OrgUnitService orgUnitService;
    private final SemesterService semesterService;
    private final CourseAccessService accessService;
    private final DataScopeService dataScopeService;
    private final AuditService auditService;
    private final Clock clock;

    public CourseService(CourseMapper courseMapper, SemesterMapper semesterMapper,
                         CourseTeacherMapper teacherMapper, CourseScheduleMapper scheduleMapper,
                         CourseSelectionMapper selectionMapper, LeaveRequestMapper leaveMapper,
                         SysUserMapper userMapper, SysOrgUnitMapper orgMapper, OrgUnitService orgUnitService,
                         SemesterService semesterService, CourseAccessService accessService,
                         DataScopeService dataScopeService, AuditService auditService, Clock clock) {
        this.courseMapper = courseMapper;
        this.semesterMapper = semesterMapper;
        this.teacherMapper = teacherMapper;
        this.scheduleMapper = scheduleMapper;
        this.selectionMapper = selectionMapper;
        this.leaveMapper = leaveMapper;
        this.userMapper = userMapper;
        this.orgMapper = orgMapper;
        this.orgUnitService = orgUnitService;
        this.semesterService = semesterService;
        this.accessService = accessService;
        this.dataScopeService = dataScopeService;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 查询

    public PageResult<CourseSummaryView> list(AuthenticatedUser user, CourseQuery q) {
        Permissions.require(user, Permissions.COURSE_READ);
        if (q.getStatus() == CourseStatus.CANCELLED && !accessService.isAdmin(user)) {
            throw BusinessException.forbidden("只有管理员可以查看已停开课程");
        }

        LambdaQueryWrapper<Course> w = new LambdaQueryWrapper<>();
        w.eq(q.getSemesterId() != null, Course::getSemesterId, q.getSemesterId())
                .eq(q.getOrgUnitId() != null, Course::getOrgUnitId, q.getOrgUnitId());
        if (q.getStatus() == null) {
            w.ne(Course::getStatus, CourseStatus.CANCELLED);
        } else {
            w.eq(Course::getStatus, q.getStatus());
        }
        if (q.getKeyword() != null && !q.getKeyword().isBlank()) {
            String kw = escapeLike(q.getKeyword().trim());
            w.and(x -> x.like(Course::getCode, kw).or().like(Course::getName, kw));
        }
        if (q.getTeacherId() != null) {
            w.exists("SELECT 1 FROM course_teacher ct WHERE ct.course_id = course.id AND ct.teacher_id = {0}",
                    q.getTeacherId());
        }
        w.orderByDesc(Course::getSemesterId).orderByAsc(Course::getCode);

        Page<Course> pageRequest = q.toPage();
        IPage<Course> page = courseMapper.selectPage(pageRequest, w);
        List<Course> courses = page.getRecords();
        Map<Long, String> semesterNames = semesterNames(courses.stream().map(Course::getSemesterId).toList());
        Map<Long, String> orgNames = orgNames(courses.stream().map(Course::getOrgUnitId).toList());
        Map<Long, List<CourseTeacherView>> teachers = teachersOf(courses.stream().map(Course::getId).toList());

        List<CourseSummaryView> items = courses.stream()
                .map(c -> summary(c, semesterNames, orgNames, teachers))
                .toList();
        return new PageResult<>(items, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public CourseDetailView get(AuthenticatedUser user, long id) {
        Permissions.require(user, Permissions.COURSE_READ);
        Course course = requireReadable(user, id);
        return detail(user, course);
    }

    /**
     * 可选课程：本学期的 OPEN 课程（排除本人已选），逐条给出可选择性判断。
     * 时间冲突、容量、窗口都会体现在 {@code blockedReason} 上，便于前端置灰与 AI 工具解释原因。
     */
    public List<AvailableCourseView> listAvailable(AuthenticatedUser user, long semesterId, String keyword) {
        Permissions.require(user, Permissions.COURSE_READ);
        Semester semester = semesterService.require(semesterId);
        LocalDateTime now = LocalDateTime.now(clock);
        boolean windowOpen = semester.selectionOpen(now);

        Set<Long> selected = new HashSet<>(selectionMapper.selectActiveCourseIds(user.userId()));
        LambdaQueryWrapper<Course> w = new LambdaQueryWrapper<Course>()
                .eq(Course::getSemesterId, semesterId)
                .eq(Course::getStatus, CourseStatus.OPEN);
        if (keyword != null && !keyword.isBlank()) {
            String kw = escapeLike(keyword.trim());
            w.and(x -> x.like(Course::getCode, kw).or().like(Course::getName, kw));
        }
        w.orderByAsc(Course::getCode);
        List<Course> courses = courseMapper.selectList(w);
        if (courses.isEmpty()) {
            return List.of();
        }

        Map<Long, List<CourseSchedule>> schedules = schedulesOf(courses.stream().map(Course::getId).toList());
        Map<Long, List<CourseTeacherView>> teachers = teachersOf(courses.stream().map(Course::getId).toList());
        Map<Long, String> myCourseNames = courseNames(selected);
        Collection<Long> mySemesterCourseIds = sameSemester(selected, semesterId);
        List<CourseSchedule> mySchedules = mySemesterCourseIds.isEmpty() ? List.of()
                : scheduleMapper.selectByCourses(mySemesterCourseIds);

        List<AvailableCourseView> result = new ArrayList<>();
        for (Course course : courses) {
            List<CourseSchedule> courseSchedules = schedules.getOrDefault(course.getId(), List.of());
            CourseBlockReason reason = null;
            String conflictName = null;
            if (!windowOpen) {
                reason = CourseBlockReason.WINDOW_CLOSED;
            } else if (selected.contains(course.getId())) {
                reason = CourseBlockReason.ALREADY_SELECTED;
            } else if (course.isFull()) {
                reason = CourseBlockReason.FULL;
            } else {
                CourseSchedule conflict = findConflict(courseSchedules, mySchedules);
                if (conflict != null) {
                    reason = CourseBlockReason.TIME_CONFLICT;
                    conflictName = myCourseNames.get(conflict.getCourseId());
                }
            }
            List<String> teacherNames = teachers.getOrDefault(course.getId(), List.of()).stream()
                    .map(CourseTeacherView::displayName).filter(Objects::nonNull).toList();
            result.add(new AvailableCourseView(course.getId(), course.getSemesterId(), course.getCode(),
                    course.getName(), course.getCredit(), value(course.getCapacity()),
                    value(course.getSelectedCount()), course.getStatus(), teacherNames,
                    courseSchedules.stream().map(ScheduleView::of).toList(),
                    reason == null, reason, conflictName));
        }
        return result;
    }

    // ------------------------------------------------------------------ 写操作

    @Transactional
    public CourseDetailView create(AuthenticatedUser user, CourseCreateRequest req) {
        if (!user.hasPermission(Permissions.COURSE_MANAGE_OWN)
                && !user.hasPermission(Permissions.COURSE_MANAGE_ALL)) {
            throw BusinessException.forbidden();
        }
        Semester semester = semesterService.requireNotClosed(req.semesterId());
        String code = req.code().trim();
        requireCodeUnique(semester.getId(), code, null);
        Long orgUnitId = resolveOrgUnit(user, req.orgUnitId());

        Course course = new Course();
        course.setSemesterId(semester.getId());
        course.setCode(code);
        course.setName(req.name().trim());
        course.setCredit(req.credit());
        course.setDescription(blankToNull(req.description()));
        course.setOrgUnitId(orgUnitId);
        course.setCapacity(req.capacity());
        course.setSelectedCount(0);
        course.setStatus(CourseStatus.DRAFT);
        course.setVersion(0);
        try {
            courseMapper.insert(course);
        } catch (DuplicateKeyException ex) {
            throw BusinessException.conflict("课程编码在该学期已存在");
        }

        teacherMapper.insert(course.getId(), user.userId(), TeacherRole.PRIMARY, user.userId());
        audit("COURSE_CREATE", course.getId(), "semesterId", semester.getId(), "code", code,
                "capacity", req.capacity());
        return detail(user, requireCourse(course.getId()));
    }

    @Transactional
    public CourseDetailView update(AuthenticatedUser user, long id, CourseUpdateRequest req) {
        Course course = requireCourse(id);
        accessService.requireManageable(user, course);
        Semester semester = semesterService.requireNotClosed(course.getSemesterId());
        if (req.capacity() < value(course.getSelectedCount())) {
            throw new BusinessException(ErrorCode.COURSE_CAPACITY_INVALID,
                    "容量不能小于已选人数 " + value(course.getSelectedCount()));
        }
        Long orgUnitId = req.orgUnitId() == null ? course.getOrgUnitId() : resolveOrgUnit(user, req.orgUnitId());

        int updated = courseMapper.update(null, new LambdaUpdateWrapper<Course>()
                .eq(Course::getId, id)
                .eq(Course::getVersion, req.version())
                .set(Course::getName, req.name().trim())
                .set(Course::getCredit, req.credit())
                .set(Course::getDescription, blankToNull(req.description()))
                .set(Course::getOrgUnitId, orgUnitId)
                .set(Course::getCapacity, req.capacity())
                .set(Course::getUpdatedBy, user.userId())
                .set(Course::getUpdatedAt, LocalDateTime.now(clock))
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw BusinessException.conflict("课程已被其他人修改，请刷新后重试");
        }
        audit("COURSE_UPDATE", id, "name", req.name(), "capacity", req.capacity(),
                "semesterId", semester.getId());
        return detail(user, requireCourse(id));
    }

    /**
     * 课程状态机：DRAFT/CLOSED → OPEN（需已排课），OPEN/DRAFT → CLOSED，任意非终态 → CANCELLED。
     * 停开是终态：自动退掉全部有效选课、清零计数，并撤销该课程待审批的请假。
     */
    @Transactional
    public CourseDetailView changeStatus(AuthenticatedUser user, long id, CourseStatusRequest req) {
        Course course = requireCourse(id);
        accessService.requireManageable(user, course);
        CourseStatus target = req.status();
        if (target == null) {
            throw BusinessException.badRequest("目标状态不能为空");
        }
        if (course.getStatus() == target) {
            return detail(user, course);
        }
        if (target == CourseStatus.DRAFT) {
            throw new BusinessException(ErrorCode.COURSE_STATUS_INVALID, "课程不能改回草稿状态");
        }
        if (course.getStatus() == CourseStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.COURSE_STATUS_INVALID, "已停开的课程不能再变更状态");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (target == CourseStatus.OPEN) {
            semesterService.requireNotClosed(course.getSemesterId());
            if (scheduleMapper.selectByCourse(id).isEmpty()) {
                throw new BusinessException(ErrorCode.COURSE_STATUS_INVALID, "开放选课前请先完成排课");
            }
        }

        int updated = courseMapper.update(null, new LambdaUpdateWrapper<Course>()
                .eq(Course::getId, id)
                .eq(Course::getStatus, course.getStatus())
                .set(Course::getStatus, target)
                .set(Course::getUpdatedBy, user.userId())
                .set(Course::getUpdatedAt, now));
        if (updated != 1) {
            throw BusinessException.conflict("课程状态已变化，请刷新后重试");
        }

        int droppedSelections = 0;
        int cancelledLeaves = 0;
        if (target == CourseStatus.CANCELLED) {
            droppedSelections = selectionMapper.dropAllForCourse(id, user.userId(), now);
            courseMapper.resetSelectedCount(id, now);
            cancelledLeaves = leaveMapper.cancelPendingForCourse(id, user.userId(), "课程已停开", now);
        }
        audit("COURSE_STATUS_CHANGE", id, "from", course.getStatus(), "to", target,
                "reason", req.reason(), "droppedSelections", droppedSelections,
                "cancelledLeaves", cancelledLeaves);
        return detail(user, requireCourse(id));
    }

    /** 添加授课教师；变更为主讲需要 course:manage_all，且原主讲自动转为助教。 */
    @Transactional
    public List<CourseTeacherView> addTeacher(AuthenticatedUser user, long courseId, CourseTeacherRequest req) {
        Course course = requireCourse(courseId);
        accessService.requireManageable(user, course);
        semesterService.requireNotClosed(course.getSemesterId());

        SysUser teacher = userMapper.selectById(req.teacherId());
        if (teacher == null || teacher.getStatus() != UserStatus.ACTIVE) {
            throw BusinessException.notFound("教师");
        }
        CourseTeacher existing = teacherMapper.selectOne(courseId, req.teacherId());
        if (req.teacherRole() == TeacherRole.PRIMARY) {
            if (!user.hasPermission(Permissions.COURSE_MANAGE_ALL)) {
                throw BusinessException.forbidden("只有管理员可以变更主讲教师");
            }
            if (existing != null && existing.getTeacherRole() == TeacherRole.PRIMARY) {
                return teachers(courseId);
            }
            CourseTeacher current = teacherMapper.selectByCourse(courseId).stream()
                    .filter(t -> t.getTeacherRole() == TeacherRole.PRIMARY).findFirst().orElse(null);
            if (current != null) {
                teacherMapper.updateRole(courseId, current.getTeacherId(), TeacherRole.ASSISTANT, user.userId());
            }
        } else if (existing != null && existing.getTeacherRole() == TeacherRole.PRIMARY) {
            throw new BusinessException(ErrorCode.COURSE_TEACHER_ROLE_INVALID,
                    "主讲教师不能被直接降级，请先指定新的主讲");
        }

        if (existing == null) {
            requireTeacherScheduleFree(courseId, req.teacherId());
            try {
                teacherMapper.insert(courseId, req.teacherId(), req.teacherRole(), user.userId());
            } catch (DuplicateKeyException ex) {
                throw BusinessException.conflict("该教师已是本课程的授课教师");
            }
        } else {
            teacherMapper.updateRole(courseId, req.teacherId(), req.teacherRole(), user.userId());
        }
        audit("COURSE_TEACHER_UPSERT", courseId, "teacherId", req.teacherId(), "role", req.teacherRole());
        return teachers(courseId);
    }

    /** 移除授课教师；主讲必须先改派，不能直接移除。 */
    @Transactional
    public List<CourseTeacherView> removeTeacher(AuthenticatedUser user, long courseId, long teacherId) {
        Course course = requireCourse(courseId);
        accessService.requireManageable(user, course);
        CourseTeacher relation = teacherMapper.selectOne(courseId, teacherId);
        if (relation == null) {
            throw BusinessException.notFound("授课教师");
        }
        if (relation.getTeacherRole() == TeacherRole.PRIMARY) {
            throw new BusinessException(ErrorCode.COURSE_TEACHER_ROLE_INVALID,
                    "请先指定新的主讲教师，再移除原主讲");
        }
        teacherMapper.delete(courseId, teacherId);
        audit("COURSE_TEACHER_REMOVE", courseId, "teacherId", teacherId);
        return teachers(courseId);
    }

    // ------------------------------------------------------------------ 供其它服务复用

    /** 批量取课程 ID → 课程名称，供知识库等模块展示课程范围。 */
    public Map<Long, String> courseNamesOf(Collection<Long> courseIds) {
        return courseNames(courseIds);
    }

    /** 批量取课程授课教师姓名（主讲在前），供课表等场景复用。 */
    public Map<Long, List<String>> teacherNamesOf(Collection<Long> courseIds) {
        Map<Long, List<String>> result = new HashMap<>();
        teachersOf(courseIds).forEach((courseId, views) -> result.put(courseId, views.stream()
                .map(CourseTeacherView::displayName).filter(Objects::nonNull).toList()));
        return result;
    }

    public Course requireCourse(long id) {
        Course course = courseMapper.selectById(id);
        if (course == null) {
            throw BusinessException.notFound("课程");
        }
        return course;
    }

    /** 读取课程：已停开课程只对任课教师与管理员可见，其余返回 404。 */
    public Course requireReadable(AuthenticatedUser user, long id) {
        Course course = requireCourse(id);
        if (course.getStatus() == CourseStatus.CANCELLED && !accessService.canSeeCancelled(user, course)) {
            throw BusinessException.notFound("课程");
        }
        return course;
    }

    public Course requireManageable(AuthenticatedUser user, long id) {
        Course course = requireCourse(id);
        accessService.requireManageable(user, course);
        return course;
    }

    /** 选课/请假前置校验用：课程是否可被该学生选。 */
    public CourseBlockReason blockReason(AuthenticatedUser user, Course course) {
        if (user == null || course == null) {
            return CourseBlockReason.COURSE_NOT_OPEN;
        }
        Semester semester = semesterService.require(course.getSemesterId());
        if (!semester.selectionOpen(LocalDateTime.now(clock))) {
            return CourseBlockReason.WINDOW_CLOSED;
        }
        if (!course.getStatus().acceptsSelection()) {
            return CourseBlockReason.COURSE_NOT_OPEN;
        }
        if (selectionMapper.selectActive(course.getId(), user.userId()) != null) {
            return CourseBlockReason.ALREADY_SELECTED;
        }
        if (course.isFull()) {
            return CourseBlockReason.FULL;
        }
        if (hasConflict(user.userId(), course)) {
            return CourseBlockReason.TIME_CONFLICT;
        }
        return null;
    }

    // ------------------------------------------------------------------ 内部

    private CourseDetailView detail(AuthenticatedUser user, Course course) {
        Map<Long, String> semesterNames = semesterNames(List.of(course.getSemesterId()));
        Map<Long, String> orgNames = orgNames(List.of(course.getOrgUnitId() == null ? -1 : course.getOrgUnitId()));
        List<CourseTeacherView> teacherViews = teachers(course.getId());
        List<ScheduleView> scheduleViews = scheduleMapper.selectByCourse(course.getId()).stream()
                .map(ScheduleView::of).toList();
        Semester semester = semesterService.require(course.getSemesterId());
        LocalDateTime now = LocalDateTime.now(clock);

        String mySelectionStatus = null;
        if (user != null && user.hasPermission(Permissions.SELECTION_SELECT)) {
            CourseSelection active = selectionMapper.selectActive(course.getId(), user.userId());
            mySelectionStatus = active == null ? null : active.getStatus().name();
        }
        CourseBlockReason reason = user != null && user.hasPermission(Permissions.SELECTION_SELECT)
                ? blockReason(user, course) : null;

        return new CourseDetailView(course.getId(), course.getSemesterId(),
                semesterNames.get(course.getSemesterId()), course.getCode(), course.getName(),
                course.getCredit(), course.getDescription(), course.getOrgUnitId(),
                orgNames.get(course.getOrgUnitId()), value(course.getCapacity()), value(course.getSelectedCount()),
                course.getStatus(), teacherViews, scheduleViews, mySelectionStatus,
                reason == null && user != null && user.hasPermission(Permissions.SELECTION_SELECT),
                reason, semester.selectionOpen(now), course.getCreatedAt(), course.getUpdatedAt(),
                value(course.getVersion()));
    }

    private CourseSummaryView summary(Course c, Map<Long, String> semesterNames, Map<Long, String> orgNames,
                                      Map<Long, List<CourseTeacherView>> teachers) {
        List<String> teacherNames = teachers.getOrDefault(c.getId(), List.of()).stream()
                .map(CourseTeacherView::displayName).filter(Objects::nonNull).toList();
        return new CourseSummaryView(c.getId(), c.getSemesterId(), semesterNames.get(c.getSemesterId()),
                c.getCode(), c.getName(), c.getCredit(), c.getDescription(), c.getOrgUnitId(),
                orgNames.get(c.getOrgUnitId()), value(c.getCapacity()), value(c.getSelectedCount()),
                c.getStatus(), teacherNames, c.getCreatedAt(), c.getUpdatedAt(), value(c.getVersion()));
    }

    private List<CourseTeacherView> teachers(long courseId) {
        return teachersOf(List.of(courseId)).getOrDefault(courseId, List.of());
    }

    /** 批量取授课教师（主讲在前），并补齐用户信息。 */
    private Map<Long, List<CourseTeacherView>> teachersOf(Collection<Long> courseIds) {
        Set<Long> ids = clean(courseIds);
        Map<Long, List<CourseTeacherView>> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        List<CourseTeacher> relations = teacherMapper.selectByCourses(ids);
        if (relations.isEmpty()) {
            return result;
        }
        Map<Long, SysUser> users = new HashMap<>();
        userMapper.selectBatchIds(relations.stream().map(CourseTeacher::getTeacherId).collect(java.util.stream.Collectors.toSet()))
                .forEach(u -> users.put(u.getId(), u));
        for (CourseTeacher relation : relations) {
            SysUser u = users.get(relation.getTeacherId());
            CourseTeacherView view = new CourseTeacherView(relation.getTeacherId(),
                    u == null ? null : u.getUsername(), u == null ? null : u.getDisplayName(),
                    relation.getTeacherRole());
            result.computeIfAbsent(relation.getCourseId(), k -> new ArrayList<>()).add(view);
        }
        result.values().forEach(list -> list.sort(Comparator.comparing(
                (CourseTeacherView v) -> v.teacherRole() == TeacherRole.PRIMARY ? 0 : 1)));
        return result;
    }

    private Map<Long, String> semesterNames(Collection<Long> ids) {
        Set<Long> clean = clean(ids);
        Map<Long, String> result = new HashMap<>();
        if (!clean.isEmpty()) {
            semesterMapper.selectBatchIds(clean).forEach(s -> result.put(s.getId(), s.getName()));
        }
        return result;
    }

    private Map<Long, String> orgNames(Collection<Long> ids) {
        Set<Long> clean = clean(ids);
        Map<Long, String> result = new HashMap<>();
        if (!clean.isEmpty()) {
            orgMapper.selectBatchIds(clean).forEach(o -> result.put(o.getId(), o.getName()));
        }
        return result;
    }

    private Map<Long, String> courseNames(Collection<Long> ids) {
        Set<Long> clean = clean(ids);
        Map<Long, String> result = new LinkedHashMap<>();
        if (!clean.isEmpty()) {
            courseMapper.selectBatchIds(clean).forEach(c -> result.put(c.getId(), c.getName()));
        }
        return result;
    }

    private Map<Long, List<CourseSchedule>> schedulesOf(Collection<Long> courseIds) {
        Set<Long> ids = clean(courseIds);
        Map<Long, List<CourseSchedule>> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        for (CourseSchedule s : scheduleMapper.selectByCourses(ids)) {
            result.computeIfAbsent(s.getCourseId(), k -> new ArrayList<>()).add(s);
        }
        return result;
    }

    /** 已选课程中属于指定学期的那些（时间冲突只在同一学期内比较）。 */
    private Collection<Long> sameSemester(Set<Long> courseIds, long semesterId) {
        if (courseIds.isEmpty()) {
            return List.of();
        }
        return courseMapper.selectBatchIds(courseIds).stream()
                .filter(c -> Objects.equals(c.getSemesterId(), semesterId))
                .map(Course::getId).toList();
    }

    private boolean hasConflict(long studentId, Course course) {
        List<CourseSchedule> target = scheduleMapper.selectByCourse(course.getId());
        if (target.isEmpty()) {
            return false;
        }
        Set<Long> selected = new HashSet<>(selectionMapper.selectActiveCourseIds(studentId));
        selected.remove(course.getId());
        if (selected.isEmpty()) {
            return false;
        }
        Collection<Long> sameSemester = sameSemester(selected, course.getSemesterId());
        if (sameSemester.isEmpty()) {
            return false;
        }
        return findConflict(target, scheduleMapper.selectByCourses(sameSemester)) != null;
    }

    private static CourseSchedule findConflict(Collection<CourseSchedule> target, Collection<CourseSchedule> mine) {
        for (CourseSchedule candidate : target) {
            for (CourseSchedule other : mine) {
                if (Objects.equals(candidate.getCourseId(), other.getCourseId())) {
                    continue;
                }
                if (ScheduleConflict.overlaps(candidate, other)) {
                    return other;
                }
            }
        }
        return null;
    }

    /** 新增授课教师前检查其在本学期的其它课程是否与当前课程时间冲突。 */
    private void requireTeacherScheduleFree(long courseId, long teacherId) {
        List<CourseSchedule> mine = scheduleMapper.selectByCourse(courseId);
        if (mine.isEmpty()) {
            return;
        }
        Course course = requireCourse(courseId);
        List<Long> otherCourses = teacherMapper.selectCourseIdsByTeachers(List.of(teacherId)).stream()
                .filter(id -> id != courseId)
                .toList();
        if (otherCourses.isEmpty()) {
            return;
        }
        Collection<Long> sameSemester = sameSemester(new HashSet<>(otherCourses), course.getSemesterId());
        if (sameSemester.isEmpty()) {
            return;
        }
        CourseSchedule conflict = findConflict(mine, scheduleMapper.selectByCourses(sameSemester));
        if (conflict != null) {
            Course other = courseMapper.selectById(conflict.getCourseId());
            throw new BusinessException(ErrorCode.COURSE_SCHEDULE_CONFLICT,
                    "该教师在该时间段已有课程：" + (other == null ? conflict.getCourseId() : other.getName()));
        }
    }

    private Long resolveOrgUnit(AuthenticatedUser user, Long orgUnitId) {
        if (orgUnitId == null) {
            return user.orgUnitId();
        }
        orgUnitService.requireActive(orgUnitId);
        if (!accessService.isAdmin(user)) {
            dataScopeService.requireManageable(user, orgUnitId, Permissions.COURSE_MANAGE_ALL);
        }
        return orgUnitId;
    }

    private void requireCodeUnique(long semesterId, String code, Long selfId) {
        long count = courseMapper.selectCount(new LambdaQueryWrapper<Course>()
                .eq(Course::getSemesterId, semesterId)
                .eq(Course::getCode, code)
                .ne(selfId != null, Course::getId, selfId));
        if (count > 0) {
            throw BusinessException.conflict("课程编码在该学期已存在");
        }
    }

    static Set<Long> clean(Collection<Long> ids) {
        Set<Long> set = new HashSet<>();
        if (ids != null) {
            ids.forEach(id -> {
                if (id != null && id > 0) {
                    set.add(id);
                }
            });
        }
        return set;
    }

    static int value(Integer n) {
        return n == null ? 0 : n;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private void audit(String action, Long targetId, Object... kv) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("COURSE", targetId);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.detail(String.valueOf(kv[i]), kv[i + 1]);
        }
        auditService.record(b.build());
    }
}
