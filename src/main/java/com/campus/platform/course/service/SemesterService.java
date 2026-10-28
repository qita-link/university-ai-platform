package com.campus.platform.course.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.course.dto.SemesterRequest;
import com.campus.platform.course.dto.SemesterView;
import com.campus.platform.course.entity.Course;
import com.campus.platform.course.entity.Semester;
import com.campus.platform.course.entity.SemesterStatus;
import com.campus.platform.course.mapper.CourseMapper;
import com.campus.platform.course.mapper.SemesterMapper;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 学期管理。
 * <p>
 * 学期状态决定课程能否创建/选课：CLOSED 后不能再建课、不能选课；
 * 选课窗口 {@code selectionStartAt/selectionEndAt} 只在学期 ACTIVE 时生效。
 */
@Service
public class SemesterService {

    static final String MODULE = "course";

    private final SemesterMapper semesterMapper;
    private final CourseMapper courseMapper;
    private final AuditService auditService;
    private final Clock clock;

    public SemesterService(SemesterMapper semesterMapper, CourseMapper courseMapper,
                           AuditService auditService, Clock clock) {
        this.semesterMapper = semesterMapper;
        this.courseMapper = courseMapper;
        this.auditService = auditService;
        this.clock = clock;
    }

    public List<SemesterView> list(AuthenticatedUser user) {
        Permissions.require(user, Permissions.COURSE_READ);
        List<Semester> semesters = semesterMapper.selectList(new LambdaQueryWrapper<Semester>()
                .orderByDesc(Semester::getStartDate)
                .orderByDesc(Semester::getId));
        LocalDateTime now = LocalDateTime.now(clock);
        return semesters.stream().map(s -> view(s, now)).toList();
    }

    public SemesterView get(AuthenticatedUser user, long id) {
        Permissions.require(user, Permissions.COURSE_READ);
        return view(require(id), LocalDateTime.now(clock));
    }

    /**
     * 当前学期：优先返回今天落在起止日期内的 ACTIVE 学期，
     * 否则返回最近一个 ACTIVE 学期；没有 ACTIVE 学期时返回 null。
     */
    public SemesterView current(AuthenticatedUser user) {
        Permissions.require(user, Permissions.COURSE_READ);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = LocalDate.now(clock);
        List<Semester> actives = semesterMapper.selectList(new LambdaQueryWrapper<Semester>()
                .eq(Semester::getStatus, SemesterStatus.ACTIVE)
                .orderByDesc(Semester::getStartDate));
        if (actives.isEmpty()) {
            return null;
        }
        Semester picked = actives.stream().filter(s -> s.covers(today)).findFirst().orElse(actives.get(0));
        return view(picked, now);
    }

    @Transactional
    public SemesterView create(AuthenticatedUser user, SemesterRequest req) {
        Permissions.require(user, Permissions.COURSE_MANAGE_ALL);
        validateRange(req);
        String code = req.code().trim();
        requireCodeUnique(code, null);

        Semester semester = new Semester();
        semester.setCode(code);
        semester.setName(req.name().trim());
        semester.setStartDate(req.startDate());
        semester.setEndDate(req.endDate());
        semester.setSelectionStartAt(req.selectionStartAt());
        semester.setSelectionEndAt(req.selectionEndAt());
        semester.setStatus(SemesterStatus.DRAFT);
        semesterMapper.insert(semester);

        audit("SEMESTER_CREATE", semester.getId(), "code", code,
                "range", req.startDate() + "~" + req.endDate());
        return view(semester, LocalDateTime.now(clock));
    }

    @Transactional
    public SemesterView update(AuthenticatedUser user, long id, SemesterRequest req) {
        Permissions.require(user, Permissions.COURSE_MANAGE_ALL);
        Semester semester = require(id);
        if (semester.getStatus().isClosed()) {
            throw new BusinessException(ErrorCode.SEMESTER_CLOSED, "学期已结束，不能再修改");
        }
        validateRange(req);
        String code = req.code().trim();
        requireCodeUnique(code, id);

        int updated = semesterMapper.update(null, new LambdaUpdateWrapper<Semester>()
                .eq(Semester::getId, id)
                .set(Semester::getCode, code)
                .set(Semester::getName, req.name().trim())
                .set(Semester::getStartDate, req.startDate())
                .set(Semester::getEndDate, req.endDate())
                .set(Semester::getSelectionStartAt, req.selectionStartAt())
                .set(Semester::getSelectionEndAt, req.selectionEndAt())
                .set(Semester::getUpdatedBy, user.userId())
                .set(Semester::getUpdatedAt, LocalDateTime.now(clock)));
        if (updated != 1) {
            throw BusinessException.conflict("学期已被其他人修改，请刷新后重试");
        }
        audit("SEMESTER_UPDATE", id, "code", code, "range", req.startDate() + "~" + req.endDate());
        return view(require(id), LocalDateTime.now(clock));
    }

    /**
     * 状态变更：DRAFT → ACTIVE / CLOSED，ACTIVE → CLOSED；CLOSED 为终态，不能再打开。
     */
    @Transactional
    public SemesterView changeStatus(AuthenticatedUser user, long id, SemesterStatus target) {
        Permissions.require(user, Permissions.COURSE_MANAGE_ALL);
        Semester semester = require(id);
        if (semester.getStatus() == target) {
            return view(semester, LocalDateTime.now(clock));
        }
        if (semester.getStatus().isClosed()) {
            throw new BusinessException(ErrorCode.SEMESTER_CLOSED, "学期已结束，不能变更状态");
        }
        if (target == SemesterStatus.DRAFT) {
            throw BusinessException.rule("学期不能回退为草稿状态");
        }
        int updated = semesterMapper.update(null, new LambdaUpdateWrapper<Semester>()
                .eq(Semester::getId, id)
                .eq(Semester::getStatus, semester.getStatus())
                .set(Semester::getStatus, target)
                .set(Semester::getUpdatedBy, user.userId())
                .set(Semester::getUpdatedAt, LocalDateTime.now(clock)));
        if (updated != 1) {
            throw BusinessException.conflict("学期状态已变化，请刷新后重试");
        }
        audit("SEMESTER_STATUS_CHANGE", id, "from", semester.getStatus(), "to", target);
        return view(require(id), LocalDateTime.now(clock));
    }

    public Semester require(long id) {
        Semester semester = semesterMapper.selectById(id);
        if (semester == null) {
            throw BusinessException.notFound("学期");
        }
        return semester;
    }

    /** 创建课程/排课前校验学期仍然开放（未结束）。 */
    public Semester requireNotClosed(long id) {
        Semester semester = require(id);
        if (semester.getStatus().isClosed()) {
            throw new BusinessException(ErrorCode.SEMESTER_CLOSED);
        }
        return semester;
    }

    private void validateRange(SemesterRequest req) {
        if (req.startDate().isAfter(req.endDate())) {
            throw BusinessException.badRequest("学期开始日期不能晚于结束日期");
        }
        if (req.selectionStartAt().isAfter(req.selectionEndAt())) {
            throw BusinessException.badRequest("选课开始时间不能晚于结束时间");
        }
    }

    private void requireCodeUnique(String code, Long selfId) {
        long count = semesterMapper.selectCount(new LambdaQueryWrapper<Semester>()
                .eq(Semester::getCode, code)
                .ne(selfId != null, Semester::getId, selfId));
        if (count > 0) {
            throw BusinessException.conflict("学期编码已存在");
        }
    }

    private SemesterView view(Semester s, LocalDateTime now) {
        long courseCount = courseMapper.selectCount(new LambdaQueryWrapper<Course>()
                .eq(Course::getSemesterId, s.getId()));
        return new SemesterView(s.getId(), s.getCode(), s.getName(), s.getStartDate(), s.getEndDate(),
                s.getSelectionStartAt(), s.getSelectionEndAt(), s.getStatus(), s.selectionOpen(now),
                courseCount, s.getCreatedAt(), s.getUpdatedAt());
    }

    private void audit(String action, Long targetId, Object... kv) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("SEMESTER", targetId);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.detail(String.valueOf(kv[i]), kv[i + 1]);
        }
        auditService.record(b.build());
    }
}
