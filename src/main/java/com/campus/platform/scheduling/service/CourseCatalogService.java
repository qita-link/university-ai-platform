package com.campus.platform.scheduling.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.course.entity.SemesterStatus;
import com.campus.platform.course.entity.WeekParity;
import com.campus.platform.scheduling.dto.CatalogDtos;
import com.campus.platform.scheduling.entity.SchClassProfile;
import com.campus.platform.scheduling.entity.SchCourse;
import com.campus.platform.scheduling.entity.SchTeachingClass;
import com.campus.platform.scheduling.entity.SchTeachingClassMember;
import com.campus.platform.scheduling.mapper.SchClassProfileMapper;
import com.campus.platform.scheduling.mapper.SchCourseMapper;
import com.campus.platform.scheduling.mapper.SchTeachingClassMapper;
import com.campus.platform.scheduling.mapper.SchTeachingClassMemberMapper;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.user.entity.OrgType;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.mapper.SysOrgUnitMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 课程库与教学班。
 * <p>
 * 课程库 {@code sch_course} 与阶段六的学期内开课 {@code course} 分开：前者是课程基本信息
 * （代码、类型、学分、总学时、默认教室要求），后者是“某学期开的一门课 + 选课”。
 * 教学班是实际参与排课的学生集合，可由多个行政班合班，也可以是行政班的部分学生。
 */
@Service
public class CourseCatalogService {

    static final String MODULE = "scheduling";
    private static final Set<String> COURSE_TYPES = Set.of("THEORY", "LAB", "PRACTICE", "MIXED");

    private final SchCourseMapper courseMapper;
    private final SchTeachingClassMapper teachingClassMapper;
    private final SchTeachingClassMemberMapper memberMapper;
    private final SchClassProfileMapper classProfileMapper;
    private final SysOrgUnitMapper orgUnitMapper;
    private final AuditService auditService;

    public CourseCatalogService(SchCourseMapper courseMapper, SchTeachingClassMapper teachingClassMapper,
                                SchTeachingClassMemberMapper memberMapper, SchClassProfileMapper classProfileMapper,
                                SysOrgUnitMapper orgUnitMapper, AuditService auditService) {
        this.courseMapper = courseMapper;
        this.teachingClassMapper = teachingClassMapper;
        this.memberMapper = memberMapper;
        this.classProfileMapper = classProfileMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ 课程库

    public List<CatalogDtos.CourseView> listCourses(AuthenticatedUser user, String keyword, String status) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        List<SchCourse> rows = courseMapper.selectList(new LambdaQueryWrapper<SchCourse>()
                .eq(status != null, SchCourse::getStatus, status)
                .and(keyword != null && !keyword.isBlank(), w -> w.like(SchCourse::getCode, keyword)
                        .or().like(SchCourse::getName, keyword))
                .orderByAsc(SchCourse::getCode));
        return rows.stream().map(this::view).toList();
    }

    public CatalogDtos.CourseView getCourse(AuthenticatedUser user, long id) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        return view(requireCourse(id));
    }

    @Transactional
    public CatalogDtos.CourseView createCourse(AuthenticatedUser user, CatalogDtos.CourseRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        validateCourse(req);
        if (courseMapper.selectCount(new LambdaQueryWrapper<SchCourse>().eq(SchCourse::getCode, req.code())) > 0) {
            throw BusinessException.conflict("课程代码 " + req.code() + " 已存在");
        }
        SchCourse row = new SchCourse();
        apply(row, req);
        row.setVersion(0);
        row.setCreatedBy(user.userId());
        row.setUpdatedBy(user.userId());
        courseMapper.insert(row);
        audit(user, "SCHEDULING_COURSE_CREATE", row.getId(), Map.of("code", row.getCode()));
        return view(row);
    }

    @Transactional
    public CatalogDtos.CourseView updateCourse(AuthenticatedUser user, long id, CatalogDtos.CourseRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        validateCourse(req);
        SchCourse row = requireCourse(id);
        if (!row.getCode().equals(req.code())
                && courseMapper.selectCount(new LambdaQueryWrapper<SchCourse>().eq(SchCourse::getCode, req.code())) > 0) {
            throw BusinessException.conflict("课程代码 " + req.code() + " 已存在");
        }
        apply(row, req);
        if (req.version() != null && !req.version().equals(row.getVersion())) {
            throw BusinessException.conflict("课程已被他人修改，请刷新后重试");
        }
        row.setUpdatedBy(user.userId());
        courseMapper.updateById(row);
        audit(user, "SCHEDULING_COURSE_UPDATE", id, Map.of("code", row.getCode()));
        return view(row);
    }

    /**
     * 停用课程（不物理删除，避免破坏历史教学任务）。停用前会检查是否存在启用的教学任务。
     */
    @Transactional
    public void disableCourse(AuthenticatedUser user, long id) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        SchCourse row = requireCourse(id);
        row.setStatus("DISABLED");
        row.setUpdatedBy(user.userId());
        courseMapper.updateById(row);
        audit(user, "SCHEDULING_COURSE_DISABLE", id, Map.of("code", row.getCode()));
    }

    // ------------------------------------------------------------------ 教学班

    public List<CatalogDtos.TeachingClassView> listTeachingClasses(AuthenticatedUser user, Long semesterId,
                                                                   String keyword) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        List<SchTeachingClass> rows = teachingClassMapper.selectList(new LambdaQueryWrapper<SchTeachingClass>()
                .eq(semesterId != null, SchTeachingClass::getSemesterId, semesterId)
                .and(keyword != null && !keyword.isBlank(), w -> w.like(SchTeachingClass::getCode, keyword)
                        .or().like(SchTeachingClass::getName, keyword))
                .orderByAsc(SchTeachingClass::getCode));
        return views(rows);
    }

    public CatalogDtos.TeachingClassView getTeachingClass(AuthenticatedUser user, long id) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        SchTeachingClass row = teachingClassMapper.selectById(id);
        if (row == null) {
            throw BusinessException.notFound("教学班");
        }
        return views(List.of(row)).get(0);
    }

    @Transactional
    public CatalogDtos.TeachingClassView createTeachingClass(AuthenticatedUser user,
                                                             CatalogDtos.TeachingClassRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        if (teachingClassMapper.selectCount(new LambdaQueryWrapper<SchTeachingClass>()
                .eq(SchTeachingClass::getSemesterId, req.semesterId())
                .eq(SchTeachingClass::getCode, req.code())) > 0) {
            throw BusinessException.conflict("同一学期内教学班编号已存在");
        }
        SchTeachingClass row = new SchTeachingClass();
        row.setSemesterId(req.semesterId());
        row.setCode(req.code().trim());
        row.setName(req.name().trim());
        row.setStudentCount(req.studentCount());
        row.setCreatedBy(user.userId());
        row.setUpdatedBy(user.userId());
        teachingClassMapper.insert(row);
        replaceMembers(row, req.members());
        audit(user, "SCHEDULING_TEACHING_CLASS_CREATE", row.getId(), Map.of("code", row.getCode()));
        return views(List.of(row)).get(0);
    }

    @Transactional
    public CatalogDtos.TeachingClassView updateTeachingClass(AuthenticatedUser user, long id,
                                                             CatalogDtos.TeachingClassRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        SchTeachingClass row = teachingClassMapper.selectById(id);
        if (row == null) {
            throw BusinessException.notFound("教学班");
        }
        row.setName(req.name().trim());
        row.setStudentCount(req.studentCount());
        row.setUpdatedBy(user.userId());
        teachingClassMapper.updateById(row);
        replaceMembers(row, req.members());
        audit(user, "SCHEDULING_TEACHING_CLASS_UPDATE", id, Map.of("code", row.getCode()));
        return views(List.of(row)).get(0);
    }

    private void replaceMembers(SchTeachingClass tc, List<CatalogDtos.MemberItem> items) {
        memberMapper.delete(new LambdaQueryWrapper<SchTeachingClassMember>()
                .eq(SchTeachingClassMember::getTeachingClassId, tc.getId()));
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("教学班至少需要一个行政班成员");
        }
        for (CatalogDtos.MemberItem item : items) {
            SysOrgUnit org = orgUnitMapper.selectById(item.classId());
            if (org == null || org.getType() != OrgType.CLASS) {
                throw BusinessException.badRequest("行政班不存在：" + item.classId());
            }
            if (!Boolean.TRUE.equals(item.fullClass())
                    && (item.studentCount() == null || item.studentCount() <= 0)) {
                throw BusinessException.badRequest("选修抽取成员必须填写学生人数：" + org.getName());
            }
            SchTeachingClassMember row = new SchTeachingClassMember();
            row.setTeachingClassId(tc.getId());
            row.setOrgUnitId(item.classId());
            row.setFullClass(Boolean.TRUE.equals(item.fullClass()));
            row.setStudentCount(Boolean.TRUE.equals(item.fullClass()) ? null : item.studentCount());
            memberMapper.insert(row);
        }
        // 未显式填写人数时，按成员人数汇总
        if (tc.getStudentCount() == null) {
            int sum = computedStudentCount(tc.getId());
            teachingClassMapper.updateById(withCount(tc, sum));
        }
    }

    private SchTeachingClass withCount(SchTeachingClass tc, int count) {
        tc.setStudentCount(count);
        return tc;
    }

    private int computedStudentCount(long teachingClassId) {
        List<SchTeachingClassMember> members = memberMapper.selectList(
                new LambdaQueryWrapper<SchTeachingClassMember>()
                        .eq(SchTeachingClassMember::getTeachingClassId, teachingClassId));
        Map<Long, Integer> classCounts = classProfileMapper.selectList(null).stream()
                .collect(Collectors.toMap(p -> p.getOrgUnitId(), p -> p.getStudentCount(), (a, b) -> a));
        int sum = 0;
        Set<Long> fullClasses = new LinkedHashSet<>();
        for (SchTeachingClassMember m : members) {
            if (Boolean.TRUE.equals(m.getFullClass())) {
                fullClasses.add(m.getOrgUnitId());
                sum += classCounts.getOrDefault(m.getOrgUnitId(), 0);
            } else {
                sum += m.getStudentCount() == null ? 0 : m.getStudentCount();
            }
        }
        return sum;
    }

    private List<CatalogDtos.TeachingClassView> views(List<SchTeachingClass> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(SchTeachingClass::getId).toList();
        List<SchTeachingClassMember> members = memberMapper.selectList(
                new LambdaQueryWrapper<SchTeachingClassMember>()
                        .in(SchTeachingClassMember::getTeachingClassId, ids));
        Map<Long, SysOrgUnit> orgs = orgUnitMapper.selectBatchIds(members.stream()
                        .map(SchTeachingClassMember::getOrgUnitId).distinct().toList()).stream()
                .collect(Collectors.toMap(SysOrgUnit::getId, o -> o, (a, b) -> a));
        // 人数按成员批量汇总：原先每条教学班再查一次成员，列表接口是 N+1
        Map<Long, Integer> classCounts = classProfileMapper.selectList(null).stream()
                .collect(Collectors.toMap(SchClassProfile::getOrgUnitId, p -> p.getStudentCount(), (a, b) -> a));
        Map<Long, Integer> counts = new LinkedHashMap<>();
        Map<Long, List<CatalogDtos.MemberView>> byTc = new LinkedHashMap<>();
        for (SchTeachingClassMember m : members) {
            SysOrgUnit org = orgs.get(m.getOrgUnitId());
            byTc.computeIfAbsent(m.getTeachingClassId(), k -> new ArrayList<>())
                    .add(new CatalogDtos.MemberView(m.getOrgUnitId(), org == null ? null : org.getName(),
                            Boolean.TRUE.equals(m.getFullClass()), m.getStudentCount()));
            int add = Boolean.TRUE.equals(m.getFullClass())
                    ? classCounts.getOrDefault(m.getOrgUnitId(), 0)
                    : (m.getStudentCount() == null ? 0 : m.getStudentCount());
            counts.merge(m.getTeachingClassId(), add, Integer::sum);
        }
        return rows.stream().map(r -> new CatalogDtos.TeachingClassView(r.getId(), r.getSemesterId(),
                r.getCode(), r.getName(), r.getStudentCount(), counts.getOrDefault(r.getId(), 0),
                byTc.getOrDefault(r.getId(), List.of()).stream()
                        .sorted(Comparator.comparing(m -> m.className() == null ? "" : m.className()))
                        .toList())).toList();
    }

    // ------------------------------------------------------------------ helpers

    public SchCourse requireCourse(long id) {
        SchCourse row = courseMapper.selectById(id);
        if (row == null) {
            throw BusinessException.notFound("课程");
        }
        return row;
    }

    private void validateCourse(CatalogDtos.CourseRequest req) {
        if (!COURSE_TYPES.contains(req.courseType())) {
            throw BusinessException.badRequest("课程类型必须是 " + COURSE_TYPES);
        }
        if (req.status() != null && !Set.of("ACTIVE", "DISABLED").contains(req.status())) {
            throw BusinessException.badRequest("课程状态必须是 ACTIVE/DISABLED");
        }
    }

    private void apply(SchCourse row, CatalogDtos.CourseRequest req) {
        row.setCode(req.code().trim());
        row.setName(req.name().trim());
        row.setCourseType(req.courseType());
        row.setCredit(req.credit() == null ? BigDecimal.ZERO : req.credit());
        row.setTotalHours(req.totalHours());
        row.setOrgUnitId(req.orgUnitId());
        row.setDefaultRoomType(req.defaultRoomType());
        row.setRequiredFeatures(req.requiredFeatures() == null ? null : String.join(",", req.requiredFeatures()));
        row.setPriority(req.priority() == null ? 5 : req.priority());
        row.setStatus(req.status() == null ? "ACTIVE" : req.status());
        row.setDescription(req.description());
    }

    private CatalogDtos.CourseView view(SchCourse c) {
        return new CatalogDtos.CourseView(c.getId(), c.getCode(), c.getName(), c.getCourseType(), c.getCredit(),
                c.getTotalHours(), c.getOrgUnitId(), c.getDefaultRoomType(),
                ResourceCatalogService.split(c.getRequiredFeatures()), c.getPriority(), c.getStatus(),
                c.getDescription(), c.getVersion());
    }

    static Set<String> validParities() {
        return Set.of(WeekParity.ALL.name(), WeekParity.ODD.name(), WeekParity.EVEN.name());
    }

    private void audit(AuthenticatedUser user, String action, Long targetId, Map<String, ?> detail) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("course_library", targetId);
        detail.forEach(b::detail);
        auditService.record(b.build());
    }
}
