package com.campus.platform.scheduling.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.course.entity.Semester;
import com.campus.platform.course.mapper.SemesterMapper;
import com.campus.platform.scheduling.dto.JobDtos;
import com.campus.platform.scheduling.dto.TaskDtos;
import com.campus.platform.scheduling.entity.SchAssignment;
import com.campus.platform.scheduling.entity.SchJob;
import com.campus.platform.scheduling.entity.SchJobLog;
import com.campus.platform.scheduling.entity.SchTask;
import com.campus.platform.scheduling.entity.SchVersion;
import com.campus.platform.scheduling.entity.SchVersionIssue;
import com.campus.platform.scheduling.entity.VersionStatus;
import com.campus.platform.scheduling.mapper.SchAssignmentMapper;
import com.campus.platform.scheduling.mapper.SchJobLogMapper;
import com.campus.platform.scheduling.mapper.SchJobMapper;
import com.campus.platform.scheduling.mapper.SchTaskMapper;
import com.campus.platform.scheduling.mapper.SchVersionIssueMapper;
import com.campus.platform.scheduling.mapper.SchVersionMapper;
import com.campus.platform.scheduling.solver.SchedulingEvaluator;
import com.campus.platform.scheduling.solver.SchedulingPrecheck;
import com.campus.platform.scheduling.solver.SchedulingSolver;
import com.campus.platform.scheduling.solver.SchedulingValidator;
import com.campus.platform.scheduling.solver.model.ConstraintCode;
import com.campus.platform.scheduling.solver.model.Issue;
import com.campus.platform.scheduling.solver.model.Placement;
import com.campus.platform.scheduling.solver.model.SchedulingProblem;
import com.campus.platform.scheduling.solver.model.SolverParams;
import com.campus.platform.scheduling.solver.model.SolverStatus;
import com.campus.platform.scheduling.solver.Violation;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 排课任务：创建、异步执行、进度、取消、重试、日志与诊断。
 * <p>
 * 并发控制：
 * <ul>
 *   <li>同一任务不能重复启动（状态机 + {@link #runningJobs} 双保险）；</li>
 *   <li>同一学期同时只允许一个求解任务，避免两个任务同时写同一版本的课表；</li>
 *   <li>求解线程池大小有限（默认 2），排队满时拒绝并给出明确错误，而不是无限堆积。</li>
 * </ul>
 * 求解结果先经独立复核器验证，通过后才落库为 DRAFT 版本；发布仍需再次复核。
 */
@Slf4j
@Service
public class SchedulingJobService {

    static final String MODULE = "scheduling";
    private static final int LOG_LIMIT = 60_000;

    private final SchJobMapper jobMapper;
    private final SchJobLogMapper jobLogMapper;
    private final SchVersionMapper versionMapper;
    private final SchAssignmentMapper assignmentMapper;
    private final SchVersionIssueMapper issueMapper;
    private final SchTaskMapper taskMapper;
    private final SemesterMapper semesterMapper;
    private final SchedulingProblemLoader loader;
    private final SchedulingValidator validator;
    private final SchedulingEvaluator evaluator;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ThreadPoolExecutor executor;

    private final Map<Long, SchedulingSolver.Handle> runningJobs = new ConcurrentHashMap<>();
    private final Map<Long, Long> runningSemesters = new ConcurrentHashMap<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public SchedulingJobService(SchJobMapper jobMapper, SchJobLogMapper jobLogMapper,
                                SchVersionMapper versionMapper, SchAssignmentMapper assignmentMapper,
                                SchVersionIssueMapper issueMapper, SchTaskMapper taskMapper,
                                SemesterMapper semesterMapper, SchedulingProblemLoader loader,
                                SchedulingValidator validator, SchedulingEvaluator evaluator,
                                AuditService auditService, ObjectMapper objectMapper, Clock clock,
                                @Value("${app.scheduling.solver-threads:2}") int threads,
                                @Value("${app.scheduling.queue-capacity:8}") int queueCapacity) {
        this.jobMapper = jobMapper;
        this.jobLogMapper = jobLogMapper;
        this.versionMapper = versionMapper;
        this.assignmentMapper = assignmentMapper;
        this.issueMapper = issueMapper;
        this.taskMapper = taskMapper;
        this.semesterMapper = semesterMapper;
        this.loader = loader;
        this.validator = validator;
        this.evaluator = evaluator;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), r -> {
                    Thread t = new Thread(r, "scheduling-solver");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    public void shutdown() {
        shuttingDown.set(true);
        runningJobs.values().forEach(SchedulingSolver.Handle::cancel);
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ 创建与启动

    @Transactional
    public JobDtos.JobView create(AuthenticatedUser user, JobDtos.JobCreateRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        Semester semester = semesterMapper.selectById(req.semesterId());
        if (semester == null) {
            throw BusinessException.notFound("学期");
        }
        loader.calendar(req.semesterId());
        String mode = req.mode() == null ? "FULL" : req.mode();
        if (!List.of("FULL", "INCREMENTAL").contains(mode)) {
            throw BusinessException.badRequest("mode 必须是 FULL 或 INCREMENTAL");
        }
        if ("INCREMENTAL".equals(mode) && req.baseVersionId() == null) {
            throw BusinessException.badRequest("增量排课必须指定 baseVersionId");
        }
        if (req.taskIds() != null && !req.taskIds().isEmpty()) {
            List<SchTask> tasks = taskMapper.selectBatchIds(req.taskIds());
            if (tasks.size() != req.taskIds().stream().distinct().count()) {
                throw BusinessException.badRequest("存在无效的教学任务 ID");
            }
            boolean otherSemester = tasks.stream().anyMatch(t -> !t.getSemesterId().equals(req.semesterId()));
            if (otherSemester) {
                throw BusinessException.badRequest("教学任务不属于指定学期");
            }
            List<Long> disabled = tasks.stream().filter(t -> !"ACTIVE".equals(t.getStatus()))
                    .map(SchTask::getId).toList();
            if (!disabled.isEmpty()) {
                throw BusinessException.badRequest("教学任务已停用，不能参与排课：" + disabled);
            }
        }
        SchJob job = new SchJob();
        job.setSemesterId(req.semesterId());
        job.setName(req.name().trim());
        job.setMode(mode);
        job.setBaseVersionId(req.baseVersionId());
        job.setStatus(SolverStatus.DRAFT);
        job.setProgress(0);
        job.setCancelRequested(false);
        job.setVersion(0);
        job.setCreatedBy(user.userId());
        job.setUpdatedBy(user.userId());
        job.setParamsJson(writeJson(normalize(req)));
        jobMapper.insert(job);
        appendLog(job.getId(), "INFO", "任务已创建：" + job.getName() + "（" + mode + "）");
        audit(user, "SCHEDULING_JOB_CREATE", job.getId(), Map.of("semesterId", req.semesterId(),
                "mode", mode));
        return view(job, semester.getName());
    }

    private JobDtos.JobCreateRequest normalize(JobDtos.JobCreateRequest req) {
        return new JobDtos.JobCreateRequest(req.semesterId(), req.name(), req.mode() == null ? "FULL" : req.mode(),
                req.baseVersionId(), req.taskIds(), req.orgUnitId(),
                req.timeLimitSeconds() == null ? 60 : req.timeLimitSeconds(),
                req.workers() == null ? 4 : req.workers(),
                req.randomSeed() == null ? 1 : req.randomSeed(),
                req.allowPartial() == null || req.allowPartial(),
                req.keepLocked() == null || req.keepLocked(), req.weightOverrides(), req.replanTaskIds());
    }

    /** 启动任务：立即返回，实际求解在受限线程池中执行。 */
    public JobDtos.JobStatusView run(AuthenticatedUser user, long jobId) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        SchJob job = requireJob(jobId);
        if (job.getStatus() == SolverStatus.RUNNING || job.getStatus() == SolverStatus.QUEUED) {
            throw BusinessException.conflict("任务正在执行，请勿重复启动");
        }
        if (job.getStatus() == SolverStatus.SUCCEEDED || job.getStatus() == SolverStatus.PARTIAL) {
            throw BusinessException.conflict("任务已完成，如需重新计算请使用重试（会创建新任务）");
        }
        Long busy = runningSemesters.get(job.getSemesterId());
        if (busy != null && busy != jobId) {
            throw BusinessException.conflict("该学期已有排课任务（ID " + busy + "）在执行，请等待其结束或取消");
        }
        jobMapper.update(null, new LambdaUpdateWrapper<SchJob>()
                .eq(SchJob::getId, jobId)
                .set(SchJob::getStatus, SolverStatus.QUEUED)
                .set(SchJob::getCancelRequested, false)
                .set(SchJob::getProgress, 0)
                .set(SchJob::getPhase, "QUEUED")
                .set(SchJob::getMessage, null)
                .set(SchJob::getStartedAt, null)
                .set(SchJob::getFinishedAt, null));
        appendLog(jobId, "INFO", "任务已提交执行队列");
        try {
            executor.execute(() -> execute(jobId, user));
        } catch (RejectedExecutionException ex) {
            jobMapper.update(null, new LambdaUpdateWrapper<SchJob>()
                    .eq(SchJob::getId, jobId)
                    .set(SchJob::getStatus, SolverStatus.FAILED)
                    .set(SchJob::getErrorCode, "QUEUE_FULL")
                    .set(SchJob::getMessage, "求解队列已满，请稍后重试")
                    .set(SchJob::getFinishedAt, LocalDateTime.now(clock)));
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "求解队列已满，请稍后重试");
        }
        audit(user, "SCHEDULING_JOB_RUN", jobId, Map.of("semesterId", job.getSemesterId()));
        return status(jobId);
    }

    @Transactional
    public JobDtos.JobView retry(AuthenticatedUser user, long jobId) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        SchJob old = requireJob(jobId);
        if (!old.getStatus().terminal()) {
            throw BusinessException.conflict("任务尚未结束，不能重试");
        }
        JobDtos.JobCreateRequest params = readParams(old.getParamsJson());
        SchJob job = new SchJob();
        job.setSemesterId(old.getSemesterId());
        job.setName(old.getName() + "（重试）");
        job.setMode(old.getMode());
        job.setBaseVersionId(old.getBaseVersionId());
        job.setStatus(SolverStatus.DRAFT);
        job.setProgress(0);
        job.setCancelRequested(false);
        job.setVersion(0);
        job.setCreatedBy(user.userId());
        job.setUpdatedBy(user.userId());
        job.setParamsJson(writeJson(params));
        jobMapper.insert(job);
        appendLog(job.getId(), "INFO", "由任务 " + jobId + " 重试创建");
        audit(user, "SCHEDULING_JOB_RETRY", job.getId(), Map.of("from", jobId));
        return view(job, semesterName(job.getSemesterId()));
    }

    public JobDtos.JobStatusView cancel(AuthenticatedUser user, long jobId) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        SchJob job = requireJob(jobId);
        if (job.getStatus() != SolverStatus.RUNNING && job.getStatus() != SolverStatus.QUEUED) {
            throw BusinessException.conflict("只有排队中或执行中的任务可以取消");
        }
        jobMapper.update(null, new LambdaUpdateWrapper<SchJob>().eq(SchJob::getId, jobId)
                .set(SchJob::getCancelRequested, true));
        SchedulingSolver.Handle handle = runningJobs.get(jobId);
        if (handle != null) {
            handle.cancel();
        } else {
            // 还在队列里，直接标记取消
            jobMapper.update(null, new LambdaUpdateWrapper<SchJob>()
                    .eq(SchJob::getId, jobId)
                    .set(SchJob::getStatus, SolverStatus.CANCELLED)
                    .set(SchJob::getPhase, "CANCELLED")
                    .set(SchJob::getFinishedAt, LocalDateTime.now(clock))
                    .set(SchJob::getMessage, "任务在排队阶段被取消"));
            appendLog(jobId, "WARN", "任务在排队阶段被取消");
        }
        audit(user, "SCHEDULING_JOB_CANCEL", jobId, Map.of());
        return status(jobId);
    }

    // ------------------------------------------------------------------ 执行

    private void execute(long jobId, AuthenticatedUser user) {
        SchJob job = jobMapper.selectById(jobId);
        if (job == null || job.getStatus() == SolverStatus.CANCELLED) {
            return;
        }
        if (runningJobs.putIfAbsent(jobId, new SchedulingSolver.Handle()) != null) {
            appendLog(jobId, "WARN", "任务已在执行，忽略重复调度");
            return;
        }
        SchedulingSolver.Handle handle = runningJobs.get(jobId);
        // 同一学期只允许一个求解：启动接口的检查发生在提交前，两次快速提交仍可能都进入队列，
        // 因此这里用 putIfAbsent 再抢一次，抢不到就明确失败，而不是两个任务同时写同一学期的课表。
        Long holder = runningSemesters.putIfAbsent(job.getSemesterId(), jobId);
        if (holder != null && holder != jobId) {
            runningJobs.remove(jobId, handle);
            appendLog(jobId, "ERROR", "该学期已有排课任务（ID " + holder + "）在执行，本次任务未启动");
            finish(jobId, SolverStatus.FAILED, "SEMESTER_BUSY",
                    "该学期已有排课任务（ID " + holder + "）在执行，请等待其结束或取消后再试",
                    System.nanoTime());
            return;
        }
        boolean acquired = holder == null;
        long startedNanos = System.nanoTime();
        try {
            jobMapper.update(null, new LambdaUpdateWrapper<SchJob>()
                    .eq(SchJob::getId, jobId)
                    .set(SchJob::getStatus, SolverStatus.RUNNING)
                    .set(SchJob::getPhase, "LOADING")
                    .set(SchJob::getProgress, 2)
                    .set(SchJob::getStartedAt, LocalDateTime.now(clock)));
            appendLog(jobId, "INFO", "开始加载排课数据");
            JobDtos.JobCreateRequest params = readParams(job.getParamsJson());

            SchedulingProblemLoader.Loaded loaded;
            try {
                loaded = loader.load(new SchedulingProblemLoader.LoadRequest(job.getSemesterId(),
                        params.taskIds(), params.orgUnitId(), params.baseVersionId(),
                        Boolean.TRUE.equals(params.keepLocked()), "INCREMENTAL".equals(job.getMode()),
                        params.replanTaskIds(), parseWeights(params.weightOverrides())));
            } catch (BusinessException ex) {
                finish(jobId, SolverStatus.FAILED, "DATA_INVALID", ex.getMessage(), startedNanos);
                return;
            }
            SchedulingProblem problem = loaded.problem();
            appendLog(jobId, "INFO", "已加载 " + loaded.tasks().size() + " 个教学任务、"
                    + problem.classes().size() + " 个行政班、" + problem.rooms().size() + " 间教室");

            List<Issue> precheck = SchedulingPrecheck.check(problem);
            long errors = precheck.stream().filter(i -> i.severity() == Issue.Severity.ERROR).count();
            for (Issue issue : precheck) {
                appendLog(jobId, issue.severity() == Issue.Severity.ERROR ? "ERROR" : "WARN",
                        "预检[" + issue.code() + "] " + issue.message());
            }
            if (errors > 0) {
                finish(jobId, SolverStatus.FAILED, "PRECHECK_FAILED",
                        "数据预检未通过（" + errors + " 项错误），请调用诊断接口查看详情", startedNanos);
                return;
            }
            updatePhase(jobId, "SOLVING", 10);

            SolverParams solverParams = new SolverParams(
                    params.timeLimitSeconds() == null ? 60 : params.timeLimitSeconds(),
                    params.workers() == null ? 4 : params.workers(),
                    params.randomSeed() == null ? 1 : params.randomSeed(),
                    params.allowPartial() == null || params.allowPartial(), true);
            SchedulingSolver solver = new SchedulingSolver();
            int[] lastPercent = {-1};
            SchedulingSolver.Outcome outcome = solver.solve(problem, solverParams, handle, progress -> {
                int percent = Math.max(10, Math.min(95, progress.percent()));
                if (percent >= lastPercent[0] + 5) {
                    lastPercent[0] = percent;
                    jobMapper.update(null, new LambdaUpdateWrapper<SchJob>()
                            .eq(SchJob::getId, jobId)
                            .set(SchJob::getProgress, percent)
                            .set(SchJob::getPhase, "SOLVING")
                            .set(SchJob::getObjective, progress.objective() == 0 ? null : progress.objective())
                            .set(SchJob::getBestBound, progress.bound() == 0 ? null : progress.bound()));
                }
            });
            appendLog(jobId, "INFO", "求解结束：状态 " + outcome.rawStatus() + "，目标值 " + outcome.objective()
                    + "，耗时 " + String.format("%.2f", outcome.wallSeconds()) + " 秒");
            if (outcome.log() != null && !outcome.log().isBlank()) {
                appendLog(jobId, "INFO", "CP-SAT 日志已记录（" + outcome.log().length() + " 字符）");
            }

            jobMapper.update(null, new LambdaUpdateWrapper<SchJob>()
                    .eq(SchJob::getId, jobId)
                    .set(SchJob::getSolverStatus, outcome.rawStatus())
                    .set(SchJob::getObjective, outcome.objective())
                    .set(SchJob::getBestBound, outcome.bestBound())
                    .set(SchJob::getStatsJson, writeJson(Map.of(
                            "candidates", outcome.stats() == null ? 0 : outcome.stats().candidates(),
                            "variables", outcome.stats() == null ? 0 : outcome.stats().variables(),
                            "constraints", outcome.stats() == null ? 0 : outcome.stats().constraints(),
                            "buildMillis", outcome.stats() == null ? 0 : outcome.stats().buildMillis(),
                            "placedSessions", outcome.placedSessions(),
                            "totalSessions", outcome.totalSessions())))
                    .set(SchJob::getLogText, truncate(outcome.log())));

            if (outcome.status() == SolverStatus.CANCELLED) {
                finish(jobId, SolverStatus.CANCELLED, "CANCELLED", "任务已被取消", startedNanos);
                return;
            }
            if (outcome.status() == SolverStatus.INFEASIBLE) {
                List<Issue> hints = new ArrayList<>(SchedulingPrecheck.infeasibleHints(problem));
                hints.forEach(i -> appendLog(jobId, "ERROR", "无解诊断[" + i.code() + "] " + i.message()));
                finish(jobId, SolverStatus.INFEASIBLE, "INFEASIBLE",
                        "求解器证明当前约束下不存在可行解；诊断信息见日志与诊断接口", startedNanos);
                return;
            }
            if (outcome.status() == SolverStatus.UNKNOWN) {
                finish(jobId, SolverStatus.UNKNOWN, "UNKNOWN",
                        "达到求解时间上限仍未确定可行性，可增大 timeLimitSeconds 或放宽约束后重试", startedNanos);
                return;
            }
            if (!outcome.status().hasSolution()) {
                finish(jobId, SolverStatus.FAILED, "SOLVER_FAILED",
                        "求解失败：" + (outcome.rawStatus() == null ? "未知" : outcome.rawStatus()), startedNanos);
                return;
            }

            updatePhase(jobId, "SAVING", 96);
            SchedulingValidator.Report report = validator.validate(problem, outcome.placements());
            SchedulingEvaluator.Report evaluation = evaluator.evaluate(problem, outcome.placements(), report);
            List<Issue> unscheduled = SchedulingPrecheck.explainUnscheduled(problem, outcome.placements());
            SchVersion version = saveVersion(user, job, loaded, outcome, report, evaluation, unscheduled);

            jobMapper.update(null, new LambdaUpdateWrapper<SchJob>()
                    .eq(SchJob::getId, jobId)
                    .set(SchJob::getResultVersionId, version.getId()));
            if (!report.violations().isEmpty()) {
                appendLog(jobId, "ERROR", "求解结果未通过独立复核：" + report.violations().size() + " 条硬约束违反");
                finish(jobId, SolverStatus.FAILED, "VALIDATION_FAILED",
                        "求解结果未通过独立硬约束复核（" + report.violations().size()
                                + " 条），已保存草稿版本供排查，禁止发布", startedNanos);
                return;
            }
            SolverStatus finalStatus = outcome.status();
            appendLog(jobId, "INFO", "已生成版本 #" + version.getVersionNo() + "，已排 "
                    + evaluation.placedSessions() + "/" + evaluation.totalSessions() + " 次课，评分 "
                    + evaluation.score());
            finish(jobId, finalStatus, null, finalStatus == SolverStatus.SUCCEEDED
                    ? "排课完成，全部课次已安排" : "排课完成，部分课次未安排（详见版本问题清单）", startedNanos);
        } catch (RuntimeException ex) {
            log.error("排课任务执行失败: jobId={}", jobId, ex);
            appendLog(jobId, "ERROR", "执行异常：" + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            finish(jobId, SolverStatus.FAILED, "INTERNAL_ERROR", "排课任务执行失败：" + ex.getMessage(),
                    startedNanos);
        } finally {
            runningJobs.remove(jobId, handle);
            if (acquired) {
                runningSemesters.remove(job.getSemesterId(), jobId);
            }
        }
    }

    private void updatePhase(long jobId, String phase, int progress) {
        jobMapper.update(null, new LambdaUpdateWrapper<SchJob>().eq(SchJob::getId, jobId)
                .set(SchJob::getPhase, phase).set(SchJob::getProgress, progress));
    }

    private SchVersion saveVersion(AuthenticatedUser user, SchJob job,
                                   SchedulingProblemLoader.Loaded loaded,
                                   SchedulingSolver.Outcome outcome, SchedulingValidator.Report report,
                                   SchedulingEvaluator.Report evaluation, List<Issue> unscheduled) {
        Integer maxNo = versionMapper.selectList(new LambdaQueryWrapper<SchVersion>()
                        .eq(SchVersion::getSemesterId, job.getSemesterId()))
                .stream().map(SchVersion::getVersionNo).max(Integer::compareTo).orElse(0);
        SchVersion version = new SchVersion();
        version.setSemesterId(job.getSemesterId());
        version.setVersionNo(maxNo + 1);
        version.setName(job.getName() + " v" + (maxNo + 1));
        version.setSource("SOLVER");
        version.setJobId(job.getId());
        version.setBaseVersionId(job.getBaseVersionId());
        version.setStatus(VersionStatus.DRAFT);
        version.setTotalSessions(evaluation.totalSessions());
        version.setPlacedSessions(evaluation.placedSessions());
        version.setHardViolations(report.violations().size());
        version.setScore(BigDecimal.valueOf(evaluation.score()));
        version.setSoftPenalty(evaluation.softPenalty());
        version.setEvaluationJson(writeJson(evaluationView(evaluation, report, loaded, outcome)));
        version.setVersion(0);
        version.setCreatedBy(user.userId());
        version.setUpdatedBy(user.userId());
        versionMapper.insert(version);

        Map<Long, SchTask> tasks = new LinkedHashMap<>();
        loaded.tasks().forEach(t -> tasks.put(t.getId(), t));
        for (Placement pl : outcome.placements()) {
            SchAssignment row = new SchAssignment();
            row.setVersionId(version.getId());
            row.setTaskId(pl.taskId());
            row.setSessionIndex(pl.sessionIndex());
            row.setDayOfWeek(pl.day());
            row.setStartPeriod(pl.startPeriod());
            SchTask task = tasks.get(pl.taskId());
            row.setEndPeriod(pl.placed() && task != null
                    ? pl.startPeriod() + task.getPeriodsPerSession() - 1 : null);
            row.setRoomId(pl.roomId());
            row.setLocked(pl.locked());
            row.setOrigin(pl.locked() ? "LOCKED" : "SOLVER");
            assignmentMapper.insert(row);
        }
        for (Violation v : report.violations()) {
            SchVersionIssue issue = new SchVersionIssue();
            issue.setVersionId(version.getId());
            issue.setSeverity("ERROR");
            issue.setCode(v.code().name());
            issue.setTaskId(v.taskId() == 0 ? null : v.taskId());
            issue.setMessage(truncate(v.message(), 1000));
            issueMapper.insert(issue);
        }
        for (Issue issue : unscheduled) {
            SchVersionIssue row = new SchVersionIssue();
            row.setVersionId(version.getId());
            row.setSeverity(issue.severity().name());
            row.setCode(issue.code());
            row.setTaskId(issue.taskId());
            row.setMessage(truncate(issue.message(), 1000));
            issueMapper.insert(row);
        }
        return version;
    }

    private com.campus.platform.scheduling.dto.ScheduleDtos.EvaluationView evaluationView(
            SchedulingEvaluator.Report evaluation, SchedulingValidator.Report report,
            SchedulingProblemLoader.Loaded loaded, SchedulingSolver.Outcome outcome) {
        return new com.campus.platform.scheduling.dto.ScheduleDtos.EvaluationView(evaluation.score(),
                evaluation.placedSessions(), evaluation.totalSessions(), evaluation.hardViolations(),
                evaluation.softPenalty(),
                evaluation.details().stream().map(d -> new com.campus.platform.scheduling.dto.ScheduleDtos
                        .SoftScoreView(d.code().name(), d.label(), d.weight(), d.applicable(), d.violated(),
                        d.penalty(), d.satisfaction())).toList(),
                ScheduleViewSupport.conflicts(report, loaded));
    }

    private void finish(long jobId, SolverStatus status, String errorCode, String message, long startedNanos) {
        jobMapper.update(null, new LambdaUpdateWrapper<SchJob>()
                .eq(SchJob::getId, jobId)
                .set(SchJob::getStatus, status)
                .set(SchJob::getPhase, status.name())
                .set(SchJob::getProgress, status == SolverStatus.SUCCEEDED ? 100 : 95)
                .set(SchJob::getErrorCode, errorCode)
                .set(SchJob::getMessage, truncate(message, 1000))
                .set(SchJob::getFinishedAt, LocalDateTime.now(clock)));
        appendLog(jobId, status == SolverStatus.FAILED || status == SolverStatus.INFEASIBLE ? "ERROR" : "INFO",
                "任务结束：" + status + "（" + message + "，耗时 "
                        + String.format("%.1f", (System.nanoTime() - startedNanos) / 1e9) + " 秒）");
        // 失败/无解/取消也写审计：记录操作人、目标对象、结果与错误码
        AuditEvent.Builder event = AuditEvent.builder(MODULE, "SCHEDULING_JOB_FINISH").target("scheduling_job",
                jobId).detail("status", status.name()).detail("message", message);
        if (status == SolverStatus.SUCCEEDED || status == SolverStatus.PARTIAL) {
            event.result(com.campus.platform.audit.entity.AuditResult.SUCCESS);
        } else {
            event.failure(errorCode == null ? status.name() : errorCode);
        }
        auditService.record(event.build());
    }

    // ------------------------------------------------------------------ 查询

    public PageResult<JobDtos.JobView> list(AuthenticatedUser user, Long semesterId, String status, int page,
                                            int size) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Page<SchJob> result = jobMapper.selectPage(Page.of(safePage, safeSize), new LambdaQueryWrapper<SchJob>()
                .eq(semesterId != null, SchJob::getSemesterId, semesterId)
                .eq(status != null && !status.isBlank(), SchJob::getStatus, status)
                .orderByDesc(SchJob::getId));
        Map<Long, String> semesters = new LinkedHashMap<>();
        result.getRecords().forEach(j -> semesters.computeIfAbsent(j.getSemesterId(), this::semesterName));
        return PageResult.of(result, j -> view(j, semesters.get(j.getSemesterId())));
    }

    public JobDtos.JobView get(AuthenticatedUser user, long id) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        SchJob job = requireJob(id);
        return view(job, semesterName(job.getSemesterId()));
    }

    public JobDtos.JobStatusView status(long id) {
        SchJob job = requireJob(id);
        long elapsed = job.getStartedAt() == null ? 0
                : Duration.between(job.getStartedAt(), job.getFinishedAt() == null
                ? LocalDateTime.now(clock) : job.getFinishedAt()).toMillis();
        return new JobDtos.JobStatusView(job.getId(), job.getStatus() == null ? null : job.getStatus().name(),
                job.getPhase(), job.getProgress() == null ? 0 : job.getProgress(), elapsed,
                job.getSolverStatus(), job.getObjective(), job.getBestBound(), job.getMessage());
    }

    public List<JobDtos.JobLogView> logs(AuthenticatedUser user, long id) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        requireJob(id);
        return jobLogMapper.selectList(new LambdaQueryWrapper<SchJobLog>()
                        .eq(SchJobLog::getJobId, id).orderByAsc(SchJobLog::getId)).stream()
                .map(l -> new JobDtos.JobLogView(l.getLevel(), l.getMessage(), l.getCreatedAt())).toList();
    }

    /** 诊断：预检问题 + 未安排原因 + 资源摘要，无解时补充资源紧张度提示。 */
    public JobDtos.DiagnosticsView diagnostics(AuthenticatedUser user, long id) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        SchJob job = requireJob(id);
        List<TaskDtos.IssueView> issues = new ArrayList<>();
        String summary;
        JobDtos.JobCreateRequest params = readParams(job.getParamsJson());
        try {
            SchedulingProblemLoader.Loaded loaded = loader.load(new SchedulingProblemLoader.LoadRequest(
                    job.getSemesterId(), params.taskIds(), params.orgUnitId(), params.baseVersionId(),
                    Boolean.TRUE.equals(params.keepLocked()), "INCREMENTAL".equals(job.getMode()),
                    params.replanTaskIds(), null));
            SchedulingProblem problem = loaded.problem();
            SchedulingPrecheck.check(problem).forEach(i -> issues.add(TaskServiceSupport.issueView(i)));
            if (job.getResultVersionId() != null) {
                List<Placement> placements = assignmentMapper.selectList(new LambdaQueryWrapper<SchAssignment>()
                                .eq(SchAssignment::getVersionId, job.getResultVersionId())).stream()
                        .map(a -> new Placement(a.getTaskId(), a.getSessionIndex(), a.getDayOfWeek(),
                                a.getStartPeriod(), a.getRoomId(), Boolean.TRUE.equals(a.getLocked())))
                        .toList();
                SchedulingPrecheck.explainUnscheduled(problem, placements)
                        .forEach(i -> issues.add(TaskServiceSupport.issueView(i)));
            } else if (job.getStatus() == SolverStatus.INFEASIBLE || job.getStatus() == SolverStatus.FAILED) {
                SchedulingPrecheck.infeasibleHints(problem).forEach(i -> issues.add(TaskServiceSupport.issueView(i)));
            }
            summary = "教学任务 " + loaded.tasks().size() + " 个；" + SchedulingPrecheck.roomCapacitySummary(problem);
        } catch (BusinessException ex) {
            issues.add(new TaskDtos.IssueView("ERROR", "DIAGNOSTIC_FAILED", null, ex.getMessage()));
            summary = ex.getMessage();
        }
        issues.addAll(jobLogMapper.selectList(new LambdaQueryWrapper<SchJobLog>()
                        .eq(SchJobLog::getJobId, id).eq(SchJobLog::getLevel, "ERROR")).stream()
                .map(l -> new TaskDtos.IssueView("ERROR", "JOB_LOG", null, l.getMessage())).toList());
        return new JobDtos.DiagnosticsView(id, job.getStatus() == null ? null : job.getStatus().name(), issues,
                summary);
    }

    // ------------------------------------------------------------------ helpers

    public SchJob requireJob(long id) {
        SchJob job = jobMapper.selectById(id);
        if (job == null) {
            throw BusinessException.notFound("排课任务");
        }
        return job;
    }

    private JobDtos.JobView view(SchJob job, String semesterName) {
        long elapsed = job.getStartedAt() == null ? 0
                : Duration.between(job.getStartedAt(), job.getFinishedAt() == null
                ? LocalDateTime.now(clock) : job.getFinishedAt()).toMillis();
        int taskCount = 0;
        int totalSessions = 0;
        JobDtos.JobCreateRequest params = readParams(job.getParamsJson());
        if (params.taskIds() != null && !params.taskIds().isEmpty()) {
            taskCount = params.taskIds().size();
        }
        if (job.getResultVersionId() != null) {
            SchVersion version = versionMapper.selectById(job.getResultVersionId());
            if (version != null) {
                totalSessions = version.getTotalSessions() == null ? 0 : version.getTotalSessions();
            }
        }
        return new JobDtos.JobView(job.getId(), job.getSemesterId(), semesterName, job.getName(), job.getMode(),
                job.getBaseVersionId(), job.getStatus() == null ? null : job.getStatus().name(),
                job.getSolverStatus(), job.getProgress(), job.getPhase(), job.getObjective(), job.getBestBound(),
                job.getResultVersionId(), job.getErrorCode(), job.getMessage(), job.getStartedAt(),
                job.getFinishedAt(), elapsed, taskCount, totalSessions, job.getStatsJson());
    }

    private String semesterName(long semesterId) {
        Semester semester = semesterMapper.selectById(semesterId);
        return semester == null ? String.valueOf(semesterId) : semester.getName();
    }

    private void appendLog(long jobId, String level, String message) {
        SchJobLog row = new SchJobLog();
        row.setJobId(jobId);
        row.setLevel(level);
        row.setMessage(truncate(message, 1000));
        jobLogMapper.insert(row);
    }

    private JobDtos.JobCreateRequest readParams(String json) {
        try {
            return objectMapper.readValue(json, JobDtos.JobCreateRequest.class);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "任务参数损坏：" + ex.getOriginalMessage());
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private static String truncate(String value) {
        return truncate(value, LOG_LIMIT);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static EnumMap<ConstraintCode, Integer> parseWeights(Map<String, Integer> overrides) {
        EnumMap<ConstraintCode, Integer> map = new EnumMap<>(ConstraintCode.class);
        if (overrides == null) {
            return map;
        }
        overrides.forEach((code, value) -> {
            try {
                ConstraintCode c = ConstraintCode.valueOf(code);
                if (!c.hard() && value != null) {
                    map.put(c, value);
                }
            } catch (IllegalArgumentException ignored) {
                // 未知代码忽略
            }
        });
        return map;
    }

    private void audit(AuthenticatedUser user, String action, Long targetId, Map<String, ?> detail) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("scheduling_job", targetId);
        detail.forEach(b::detail);
        auditService.record(b.build());
    }
}
