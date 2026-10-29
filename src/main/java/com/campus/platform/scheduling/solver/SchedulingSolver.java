package com.campus.platform.scheduling.solver;

import com.campus.platform.scheduling.solver.model.ConstraintCode;
import com.campus.platform.scheduling.solver.model.Issue;
import com.campus.platform.scheduling.solver.model.Placement;
import com.campus.platform.scheduling.solver.model.SchedulingProblem;
import com.campus.platform.scheduling.solver.model.SolverParams;
import com.campus.platform.scheduling.solver.model.SolverStatus;
import com.campus.platform.scheduling.solver.model.TaskDef;
import com.google.ortools.Loader;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverSolutionCallback;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * CP-SAT 求解器封装（不依赖 Spring，可脱离数据库单元测试）。
 * <p>
 * 流程：候选生成 → 模型构建 → 求解（可取消、可上报进度）→ 结果映射与状态归类。
 * 求解结果只是“候选”，硬约束是否真的满足由 {@link SchedulingValidator} 独立复核。
 */
public final class SchedulingSolver {

    private static final AtomicBoolean NATIVE_LOADED = new AtomicBoolean(false);
    private static volatile String nativeError;

    /** 求解进度事件。 */
    public record Progress(String phase, int percent, long objective, long bound, double seconds) {
    }

    /** 求解结果。placements 按 (任务, 课次) 全量给出，未安排的课次 day 为空。 */
    public record Outcome(SolverStatus status, String rawStatus, List<Placement> placements,
                          List<CandidateGenerator.TaskCandidates> taskCandidates,
                          long objective, long bestBound, double wallSeconds, String log,
                          SchedulingModelBuilder.ModelStats stats, List<Issue> issues,
                          int placedSessions, int totalSessions) {
    }

    /** 求解句柄：支持从其它线程取消。 */
    public static final class Handle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile CpSolver solver;

        public void cancel() {
            cancelled.set(true);
            CpSolver s = solver;
            if (s != null) {
                try {
                    s.stopSearch();
                } catch (RuntimeException ignored) {
                    // 求解已结束
                }
            }
        }

        public boolean cancelled() {
            return cancelled.get();
        }
    }

    /** 加载 OR-Tools 原生库（首次调用时执行一次）。 */
    public static void ensureNativeLoaded() {
        if (NATIVE_LOADED.get()) {
            return;
        }
        synchronized (NATIVE_LOADED) {
            if (NATIVE_LOADED.get()) {
                return;
            }
            try {
                Loader.loadNativeLibraries();
                NATIVE_LOADED.set(true);
            } catch (RuntimeException | UnsatisfiedLinkError ex) {
                nativeError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                throw new IllegalStateException("OR-Tools 原生库加载失败（当前平台可能不受支持）：" + nativeError, ex);
            }
        }
    }

    public static String nativeError() {
        return nativeError;
    }

    public Outcome solve(SchedulingProblem p, SolverParams params, Handle handle, Consumer<Progress> progress) {
        ensureNativeLoaded();
        long start = System.nanoTime();
        List<Issue> issues = new ArrayList<>();

        List<CandidateGenerator.TaskCandidates> tcs = CandidateGenerator.generate(p, p.tasks(),
                baselineByTask(p));
        for (CandidateGenerator.TaskCandidates tc : tcs) {
            if (tc.candidates().isEmpty()) {
                issues.add(Issue.error("NO_CANDIDATE", tc.task().id(),
                        "教学任务 " + tc.task().label() + " 没有可行候选：" + tc.explainEmpty()));
            }
        }
        if (progress != null) {
            progress.accept(new Progress("BUILDING", 5, 0, 0, (System.nanoTime() - start) / 1e9));
        }

        SchedulingModelBuilder.Built built = SchedulingModelBuilder.build(p, tcs, params);
        issues.addAll(built.issues());
        if (progress != null) {
            progress.accept(new Progress("SOLVING", 10, 0, 0, (System.nanoTime() - start) / 1e9));
        }

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(params.timeLimitSeconds());
        solver.getParameters().setNumSearchWorkers(params.workers());
        solver.getParameters().setRandomSeed(params.randomSeed());
        solver.getParameters().setLogSearchProgress(params.logSearch());
        handle.solver = solver;

        StringBuilder log = new StringBuilder();
        if (params.logSearch()) {
            solver.setLogCallback(line -> {
                if (log.length() < 60_000) {
                    log.append(line).append('\n');
                }
            });
        }

        CpSolverStatus raw;
        try {
            if (handle.cancelled()) {
                return cancelledOutcome(p, tcs, built, start, log);
            }
            raw = solver.solve(built.model(), new ProgressCallback(params.timeLimitSeconds(), start, handle, progress));
        } catch (RuntimeException ex) {
            issues.add(Issue.error("SOLVER_ERROR", null, "求解器执行异常：" + ex.getClass().getSimpleName()
                    + ": " + ex.getMessage()));
            return new Outcome(SolverStatus.FAILED, "ERROR", List.of(), tcs, 0, 0,
                    (System.nanoTime() - start) / 1e9, log.toString(), built.stats(), issues, 0, totalSessions(p));
        } finally {
            handle.solver = null;
            solver.clearLogCallback();
        }

        if (handle.cancelled()) {
            return cancelledOutcome(p, tcs, built, start, log);
        }

        if (raw == CpSolverStatus.FEASIBLE || raw == CpSolverStatus.OPTIMAL) {
            List<Placement> placements = extract(p, built, solver);
            int placed = (int) placements.stream().filter(Placement::placed).count();
            int total = totalSessions(p);
            SolverStatus status = placed >= total ? SolverStatus.SUCCEEDED : SolverStatus.PARTIAL;
            return new Outcome(status, raw.name(), placements, tcs, (long) solver.objectiveValue(),
                    (long) solver.bestObjectiveBound(), (System.nanoTime() - start) / 1e9, log.toString(),
                    built.stats(), issues, placed, total);
        }

        SolverStatus status = raw == CpSolverStatus.INFEASIBLE ? SolverStatus.INFEASIBLE
                : raw == CpSolverStatus.MODEL_INVALID ? SolverStatus.FAILED : SolverStatus.UNKNOWN;
        if (raw == CpSolverStatus.MODEL_INVALID) {
            issues.add(Issue.error("MODEL_INVALID", null, "CP-SAT 判定模型无效，请检查任务学时与候选生成"));
        }
        return new Outcome(status, raw.name(), List.of(), tcs, 0, 0,
                (System.nanoTime() - start) / 1e9, log.toString(), built.stats(), issues, 0, totalSessions(p));
    }

    private Outcome cancelledOutcome(SchedulingProblem p, List<CandidateGenerator.TaskCandidates> tcs,
                                     SchedulingModelBuilder.Built built, long start, StringBuilder log) {
        return new Outcome(SolverStatus.CANCELLED, "CANCELLED", List.of(), tcs, 0, 0,
                (System.nanoTime() - start) / 1e9, log.toString(), built.stats(),
                List.of(Issue.warning("CANCELLED", null, "任务已被取消")), 0, totalSessions(p));
    }

    static int totalSessions(SchedulingProblem p) {
        return p.tasks().stream().mapToInt(TaskDef::sessionsPerWeek).sum();
    }

    private static Map<Long, List<Placement>> baselineByTask(SchedulingProblem p) {
        Map<Long, List<Placement>> m = new LinkedHashMap<>();
        for (Placement b : p.baseline()) {
            m.computeIfAbsent(b.taskId(), k -> new ArrayList<>()).add(b);
        }
        return m;
    }

    /** 从求解结果读回每节课的 (星期, 起始节, 教室)，锁定的课次保持原课次号。 */
    private static List<Placement> extract(SchedulingProblem p, SchedulingModelBuilder.Built built, CpSolver solver) {
        Map<Long, List<Candidate>> chosen = new HashMap<>();
        for (int i = 0; i < built.vars().length; i++) {
            if (solver.booleanValue(built.vars()[i])) {
                Candidate c = built.candidates().get(i);
                long taskId = built.taskCandidates().get(c.taskIndex()).task().id();
                chosen.computeIfAbsent(taskId, k -> new ArrayList<>()).add(c);
            }
        }
        List<Placement> out = new ArrayList<>();
        for (TaskDef t : p.tasks()) {
            List<Candidate> cs = new ArrayList<>(chosen.getOrDefault(t.id(), List.of()));
            cs.sort(Comparator.comparingInt(Candidate::day).thenComparingInt(Candidate::start));
            int sessions = t.sessionsPerWeek();
            Placement[] slots = new Placement[sessions];
            for (Placement lock : p.locked()) {
                if (lock.taskId() == t.id() && lock.sessionIndex() >= 1 && lock.sessionIndex() <= sessions) {
                    boolean already = cs.stream().anyMatch(c -> c.day() == lock.day()
                            && c.start() == lock.startPeriod());
                    slots[lock.sessionIndex() - 1] = new Placement(t.id(), lock.sessionIndex(), lock.day(),
                            lock.startPeriod(), lock.roomId(),
                            already || !lock.placed());
                    if (already) {
                        cs.removeIf(c -> c.day() == lock.day() && c.start() == lock.startPeriod());
                    }
                }
            }
            int idx = 0;
            for (Candidate c : cs) {
                while (idx < sessions && slots[idx] != null) {
                    idx++;
                }
                if (idx >= sessions) {
                    break;
                }
                slots[idx] = new Placement(t.id(), idx + 1, c.day(), c.start(), c.roomId(), false);
            }
            for (int i = 0; i < sessions; i++) {
                out.add(slots[i] != null ? slots[i] : new Placement(t.id(), i + 1, null, null, null, false));
            }
        }
        return out;
    }

    /** 上报目标值改进；同时响应取消与时间上限。 */
    private static final class ProgressCallback extends CpSolverSolutionCallback {
        private final double limitSeconds;
        private final long startNanos;
        private final Handle handle;
        private final Consumer<Progress> listener;
        private long best = Long.MAX_VALUE;

        ProgressCallback(double limitSeconds, long startNanos, Handle handle, Consumer<Progress> listener) {
            this.limitSeconds = limitSeconds;
            this.startNanos = startNanos;
            this.handle = handle;
            this.listener = listener;
        }

        @Override
        public void onSolutionCallback() {
            if (handle.cancelled()) {
                stopSearch();
                return;
            }
            long objective = (long) objectiveValue();
            double elapsed = (System.nanoTime() - startNanos) / 1e9;
            if (objective < best) {
                best = objective;
                if (listener != null) {
                    int percent = Math.min(95, 10 + (int) (85 * Math.min(1.0, elapsed / Math.max(1, limitSeconds))));
                    listener.accept(new Progress("SOLVING", percent, objective, (long) bestObjectiveBound(),
                            elapsed));
                }
            }
        }
    }

    /** 直接读某个变量（供测试使用）。 */
    static long valueOf(CpModel model, IntVar var, CpSolver solver) {
        return solver.value(var);
    }
}
