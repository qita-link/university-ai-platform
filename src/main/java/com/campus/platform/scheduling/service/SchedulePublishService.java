package com.campus.platform.scheduling.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.course.entity.Semester;
import com.campus.platform.course.mapper.SemesterMapper;
import com.campus.platform.scheduling.dto.ScheduleDtos;
import com.campus.platform.scheduling.entity.SchPublishRecord;
import com.campus.platform.scheduling.entity.SchVersion;
import com.campus.platform.scheduling.entity.VersionStatus;
import com.campus.platform.scheduling.mapper.SchPublishRecordMapper;
import com.campus.platform.scheduling.mapper.SchVersionMapper;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 课表发布与撤销发布。
 * <p>
 * 发布前强制复核：存在硬约束违反（含学时不一致、冲突、锁定被改）一律拒绝；
 * 同学期发布新版本时，旧版本自动归档（数据库唯一索引保证只有一个 PUBLISHED）。
 */
@Service
public class SchedulePublishService {

    static final String MODULE = "scheduling";

    private final SchVersionMapper versionMapper;
    private final SchPublishRecordMapper publishMapper;
    private final ScheduleQueryService queryService;
    private final SemesterMapper semesterMapper;
    private final SysUserMapper userMapper;
    private final AuditService auditService;
    private final Clock clock;

    public SchedulePublishService(SchVersionMapper versionMapper, SchPublishRecordMapper publishMapper,
                                  ScheduleQueryService queryService, SemesterMapper semesterMapper,
                                  SysUserMapper userMapper, AuditService auditService, Clock clock) {
        this.versionMapper = versionMapper;
        this.publishMapper = publishMapper;
        this.queryService = queryService;
        this.semesterMapper = semesterMapper;
        this.userMapper = userMapper;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public ScheduleDtos.VersionView publish(AuthenticatedUser user, long versionId,
                                            ScheduleDtos.PublishRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_PUBLISH);
        SchVersion version = queryService.requireVersion(versionId);
        if (version.getStatus() == VersionStatus.PUBLISHED) {
            throw BusinessException.conflict("该版本已是发布状态");
        }
        if (version.getStatus() == VersionStatus.ARCHIVED) {
            throw BusinessException.conflict("已归档版本不能发布，请先复制为新版本");
        }
        ScheduleDtos.EvaluationView evaluation = queryService.evaluate(version);
        if (evaluation.hardViolations() > 0) {
            String detail = evaluation.conflicts().stream().limit(3)
                    .map(ScheduleDtos.ConflictView::message).reduce((a, b) -> a + "；" + b).orElse("");
            throw BusinessException.rule("发布前复核未通过：" + evaluation.hardViolations()
                    + " 条硬约束违反，不能发布。" + detail);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        for (SchVersion published : versionMapper.selectList(new LambdaQueryWrapper<SchVersion>()
                .eq(SchVersion::getSemesterId, version.getSemesterId())
                .eq(SchVersion::getStatus, VersionStatus.PUBLISHED))) {
            versionMapper.update(null, new LambdaUpdateWrapper<SchVersion>()
                    .eq(SchVersion::getId, published.getId())
                    .set(SchVersion::getStatus, VersionStatus.ARCHIVED));
            record(user, version.getSemesterId(), published.getId(), "ARCHIVE",
                    "发布新版本 " + version.getName() + " 时自动归档");
        }
        versionMapper.update(null, new LambdaUpdateWrapper<SchVersion>()
                .eq(SchVersion::getId, versionId)
                .set(SchVersion::getStatus, VersionStatus.PUBLISHED)
                .set(SchVersion::getPublishedAt, now)
                .set(SchVersion::getPublishedBy, user.userId())
                .set(SchVersion::getHardViolations, 0)
                .set(SchVersion::getPlacedSessions, evaluation.placedSessions())
                .set(SchVersion::getScore, java.math.BigDecimal.valueOf(evaluation.score()))
                .set(SchVersion::getUpdatedBy, user.userId()));
        record(user, version.getSemesterId(), versionId, "PUBLISH", req == null ? null : req.reason());
        audit(user, "SCHEDULING_VERSION_PUBLISH", versionId, Map.of("semesterId", version.getSemesterId(),
                "placedSessions", evaluation.placedSessions(), "score", evaluation.score()));
        SchVersion refreshed = queryService.requireVersion(versionId);
        return queryService.getVersion(user, refreshed.getId());
    }

    @Transactional
    public ScheduleDtos.VersionView unpublish(AuthenticatedUser user, long versionId,
                                              ScheduleDtos.PublishRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_PUBLISH);
        SchVersion version = queryService.requireVersion(versionId);
        if (version.getStatus() != VersionStatus.PUBLISHED) {
            throw BusinessException.conflict("只有已发布版本可以撤销发布");
        }
        versionMapper.update(null, new LambdaUpdateWrapper<SchVersion>()
                .eq(SchVersion::getId, versionId)
                .set(SchVersion::getStatus, VersionStatus.ARCHIVED)
                .set(SchVersion::getPublishedAt, null)
                .set(SchVersion::getPublishedBy, null)
                .set(SchVersion::getUpdatedBy, user.userId()));
        record(user, version.getSemesterId(), versionId, "UNPUBLISH", req == null ? null : req.reason());
        audit(user, "SCHEDULING_VERSION_UNPUBLISH", versionId, Map.of("semesterId", version.getSemesterId()));
        return queryService.getVersion(user, versionId);
    }

    public List<ScheduleDtos.PublishRecordView> records(AuthenticatedUser user, Long semesterId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        List<SchPublishRecord> rows = publishMapper.selectList(new LambdaQueryWrapper<SchPublishRecord>()
                .eq(semesterId != null, SchPublishRecord::getSemesterId, semesterId)
                .orderByDesc(SchPublishRecord::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, SchVersion> versions = versionMapper.selectBatchIds(rows.stream()
                        .map(SchPublishRecord::getVersionId).distinct().toList()).stream()
                .collect(Collectors.toMap(SchVersion::getId, v -> v, (a, b) -> a));
        Map<Long, String> users = new LinkedHashMap<>();
        List<Long> operatorIds = rows.stream().map(SchPublishRecord::getOperatorId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (!operatorIds.isEmpty()) {
            userMapper.selectBatchIds(operatorIds).forEach(u -> users.put(u.getId(), u.getDisplayName()));
        }
        List<ScheduleDtos.PublishRecordView> out = new ArrayList<>();
        for (SchPublishRecord r : rows) {
            SchVersion v = versions.get(r.getVersionId());
            out.add(new ScheduleDtos.PublishRecordView(r.getId(), r.getSemesterId(), r.getVersionId(),
                    v == null ? null : v.getName(), r.getAction(), r.getOperatorId(),
                    r.getOperatorId() == null ? null : users.get(r.getOperatorId()), r.getReason(),
                    r.getCreatedAt()));
        }
        return out;
    }

    private void record(AuthenticatedUser user, long semesterId, long versionId, String action, String reason) {
        SchPublishRecord row = new SchPublishRecord();
        row.setSemesterId(semesterId);
        row.setVersionId(versionId);
        row.setAction(action);
        row.setOperatorId(user.userId());
        row.setReason(reason == null ? null : reason.length() > 500 ? reason.substring(0, 500) : reason);
        publishMapper.insert(row);
    }

    private void audit(AuthenticatedUser user, String action, Long targetId, Map<String, ?> detail) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("schedule_version", targetId);
        detail.forEach(b::detail);
        auditService.record(b.build());
    }

    static String semesterName(Semester semester) {
        return semester == null ? null : semester.getName();
    }

    static String userName(SysUser user) {
        return user == null ? null : user.getDisplayName();
    }
}
