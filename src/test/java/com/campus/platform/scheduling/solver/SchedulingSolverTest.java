package com.campus.platform.scheduling.solver;

import com.campus.platform.scheduling.solver.model.ClassDef;
import com.campus.platform.scheduling.solver.model.ConstraintCode;
import com.campus.platform.scheduling.solver.model.PeriodDef;
import com.campus.platform.scheduling.solver.model.Placement;
import com.campus.platform.scheduling.solver.model.RoomDef;
import com.campus.platform.scheduling.solver.model.RuleTarget;
import com.campus.platform.scheduling.solver.model.SchedulingProblem;
import com.campus.platform.scheduling.solver.model.SolverParams;
import com.campus.platform.scheduling.solver.model.SolverStatus;
import com.campus.platform.scheduling.solver.model.TaskDef;
import com.campus.platform.scheduling.solver.model.TeacherDef;
import com.campus.platform.scheduling.solver.model.TeachingClassDef;
import com.campus.platform.scheduling.solver.model.TimeRule;
import com.campus.platform.scheduling.solver.model.TimeRuleKind;
import com.campus.platform.scheduling.solver.model.Weeks;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CP-SAT 求解器与独立复核器的算法测试（不依赖数据库）。
 * <p>
 * 约束存在多解时不要求唯一课表，只断言硬约束满足、业务目标与评分一致。
 */
class SchedulingSolverTest {

    private final SchedulingSolver solver = new SchedulingSolver();
    private final SchedulingValidator validator = new SchedulingValidator();
    private final SchedulingEvaluator evaluator = new SchedulingEvaluator();

    private static final Set<Integer> DAYS = Set.of(1, 2, 3, 4, 5);

    // ------------------------------------------------------------------ 场景一

    @Test
    void minimalProblemIsSolvedAndPassesIndependentValidation() {
        SchedulingProblem p = base()
                .rooms(List.of(room(11, "A101", 60), room(12, "A102", 60)))
                .teachers(List.of(teacher(1, "张老师"), teacher(2, "李老师")))
                .classes(List.of(new ClassDef(101, "软件2401", 40, null, null)))
                .teachingClasses(List.of(new TeachingClassDef(201, "软件2401-1", 40,
                        List.of(new TeachingClassDef.Member(101, true)))))
                .tasks(List.of(task(301, "Java 程序设计", 201, List.of(1L), 2, 2),
                        task(302, "数据结构", 201, List.of(2L), 2, 2)))
                .build();

        SchedulingSolver.Outcome out = solve(p);

        assertThat(out.status()).isEqualTo(SolverStatus.SUCCEEDED);
        assertThat(out.placements()).hasSize(4).allMatch(Placement::placed);
        SchedulingValidator.Report report = validator.validate(p, out.placements());
        assertThat(report.violations()).isEmpty();
        assertThat(evaluator.evaluate(p, out.placements(), report).placedSessions()).isEqualTo(4);
    }

    // ------------------------------------------------------------------ 场景二

    @Test
    void sameTeacherNeverTeachesTwoCoursesAtOnce() {
        SchedulingProblem p = base()
                .rooms(List.of(room(11, "A101", 60), room(12, "A102", 60)))
                .teachers(List.of(teacher(1, "张老师")))
                .classes(List.of(new ClassDef(101, "软件2401", 40, null, null),
                        new ClassDef(102, "软件2402", 40, null, null)))
                .teachingClasses(List.of(tc(201, 101, 40), tc(202, 102, 40)))
                .tasks(List.of(task(301, "Java", 201, List.of(1L), 3, 2),
                        task(302, "数据库", 202, List.of(1L), 3, 2)))
                .build();

        SchedulingSolver.Outcome out = solve(p);

        assertThat(out.status()).isEqualTo(SolverStatus.SUCCEEDED);
        assertThat(validator.validate(p, out.placements()).violations()).isEmpty();
        assertThat(overlaps(out.placements(), 301, 302)).isZero();
    }

    // ------------------------------------------------------------------ 场景三

    @Test
    void singleRoomCannotHostTwoCoursesAtTheSameTime() {
        SchedulingProblem p = base()
                .rooms(List.of(room(11, "A101", 60)))
                .teachers(List.of(teacher(1, "张老师"), teacher(2, "李老师")))
                .classes(List.of(new ClassDef(101, "软件2401", 40, null, null)))
                .teachingClasses(List.of(tc(201, 101, 40)))
                .tasks(List.of(task(301, "Java", 201, List.of(1L), 2, 2),
                        task(302, "数据库", 201, List.of(2L), 2, 2)))
                .build();

        SchedulingSolver.Outcome out = solve(p);

        assertThat(out.status()).isEqualTo(SolverStatus.SUCCEEDED);
        assertThat(out.placements()).allMatch(pl -> pl.roomId() != null && pl.roomId() == 11L);
        assertThat(validator.validate(p, out.placements()).violations()).isEmpty();
    }

    // ------------------------------------------------------------------ 场景四

    @Test
    void sameAdministrativeClassHasNoOverlappingRequiredCourses() {
        SchedulingProblem p = base()
                .rooms(List.of(room(11, "A101", 60), room(12, "A102", 60)))
                .teachers(List.of(teacher(1, "张老师"), teacher(2, "李老师")))
                .classes(List.of(new ClassDef(101, "软件2401", 40, null, null)))
                .teachingClasses(List.of(tc(201, 101, 40), tc(202, 101, 40)))
                .tasks(List.of(task(301, "Java", 201, List.of(1L), 4, 2),
                        task(302, "数据库", 202, List.of(2L), 4, 2)))
                .build();

        SchedulingSolver.Outcome out = solve(p);

        assertThat(out.status()).isEqualTo(SolverStatus.SUCCEEDED);
        assertThat(validator.validate(p, out.placements()).violations()).isEmpty();
    }

    // ------------------------------------------------------------------ 场景五

    @Test
    void classLargerThanEveryRoomIsReportedAsUnschedulable() {
        SchedulingProblem p = base()
                .rooms(List.of(room(11, "A101", 40)))
                .teachers(List.of(teacher(1, "张老师")))
                .classes(List.of(new ClassDef(101, "大数据2401", 120, null, null)))
                .teachingClasses(List.of(tc(201, 101, 120)))
                .tasks(List.of(task(301, "Java", 201, List.of(1L), 2, 2)))
                .build();

        List<com.campus.platform.scheduling.solver.model.Issue> precheck = SchedulingPrecheck.check(p);
        assertThat(precheck).anyMatch(i -> i.code().equals("NO_CANDIDATE")
                && i.message().contains("容量"));

        SchedulingSolver.Outcome out = solve(p);
        assertThat(out.status()).isEqualTo(SolverStatus.PARTIAL);
        assertThat(out.placedSessions()).isZero();
        assertThat(SchedulingPrecheck.explainUnscheduled(p, out.placements()))
                .anyMatch(i -> i.taskId() != null && i.taskId() == 301L);
    }

    // ------------------------------------------------------------------ 场景六

    @Test
    void teacherUnavailableSlotIsNeverUsed() {
        SchedulingProblem p = base()
                .rooms(List.of(room(11, "A101", 60)))
                .teachers(List.of(teacher(1, "张老师")))
                .classes(List.of(new ClassDef(101, "软件2401", 40, null, null)))
                .teachingClasses(List.of(tc(201, 101, 40)))
                .tasks(List.of(task(301, "Java", 201, List.of(1L), 2, 2)))
                .rules(List.of(forbidden(RuleTarget.TEACHER, 1L, 1, 1, 8)))
                .build();

        SchedulingSolver.Outcome out = solve(p);

        assertThat(out.status()).isEqualTo(SolverStatus.SUCCEEDED);
        assertThat(out.placements()).filteredOn(Placement::placed).allMatch(pl -> pl.day() != 1);
        assertThat(validator.validate(p, out.placements()).violations()).isEmpty();
    }

    // ------------------------------------------------------------------ 场景七

    @Test
    void doublePeriodCourseStaysInsideOneSection() {
        List<PeriodDef> periods = new ArrayList<>();
        periods.add(new PeriodDef(1, "MORNING", 480, 525));
        periods.add(new PeriodDef(2, "MORNING", 530, 575));
        periods.add(new PeriodDef(3, "MORNING", 580, 625));
        periods.add(new PeriodDef(4, "MORNING", 630, 675));
        periods.add(new PeriodDef(5, "AFTERNOON", 810, 855));
        periods.add(new PeriodDef(6, "AFTERNOON", 860, 905));

        SchedulingProblem p = SchedulingProblem.builder()
                .teachingWeeks(16)
                .teachingDays(DAYS)
                .periods(periods)
                .rooms(List.of(room(11, "A101", 60)))
                .teachers(List.of(teacher(1, "张老师")))
                .classes(List.of(new ClassDef(101, "软件2401", 40, null, null)))
                .teachingClasses(List.of(tc(201, 101, 40)))
                .tasks(List.of(task(301, "实验", 201, List.of(1L), 2, 2)))
                .build();

        SchedulingSolver.Outcome out = solve(p);

        assertThat(out.status()).isEqualTo(SolverStatus.SUCCEEDED);
        assertThat(out.placements()).filteredOn(Placement::placed)
                // 午休（第 4 节 → 第 5 节）之间不能连堂
                .noneMatch(pl -> pl.startPeriod() == 4)
                .allMatch(pl -> p.validSpan(pl.startPeriod(), 2));
        assertThat(validator.validate(p, out.placements()).violations()).isEmpty();
    }

    // ------------------------------------------------------------------ 场景八

    @Test
    void fixedTimeRuleIsRespected() {
        SchedulingProblem p = base()
                .rooms(List.of(room(11, "A101", 60), room(12, "A102", 60)))
                .teachers(List.of(teacher(1, "张老师")))
                .classes(List.of(new ClassDef(101, "软件2401", 40, null, null)))
                .teachingClasses(List.of(tc(201, 101, 40)))
                .tasks(List.of(task(301, "专题课", 201, List.of(1L), 1, 2)))
                .rules(List.of(fixed(RuleTarget.TASK, 301L, 3, 3, 4)))
                .build();

        SchedulingSolver.Outcome out = solve(p);

        assertThat(out.status()).isEqualTo(SolverStatus.SUCCEEDED);
        Placement pl = out.placements().get(0);
        assertThat(pl.day()).isEqualTo(3);
        assertThat(pl.startPeriod()).isEqualTo(3);
        assertThat(validator.validate(p, out.placements()).violations()).isEmpty();
    }

    // ------------------------------------------------------------------ 场景九

    @Test
    void lockedPlacementIsKeptWhenRescheduling() {
        SchedulingProblem p = base()
                .rooms(List.of(room(11, "A101", 60), room(12, "A102", 60)))
                .teachers(List.of(teacher(1, "张老师")))
                .classes(List.of(new ClassDef(101, "软件2401", 40, null, null)))
                .teachingClasses(List.of(tc(201, 101, 40)))
                .tasks(List.of(task(301, "Java", 201, List.of(1L), 2, 2)))
                .locked(List.of(new Placement(301, 1, 2, 1, 11L, true)))
                .build();

        SchedulingSolver.Outcome out = solve(p);

        assertThat(out.status()).isEqualTo(SolverStatus.SUCCEEDED);
        Placement first = out.placements().stream().filter(pl -> pl.sessionIndex() == 1).findFirst().orElseThrow();
        assertThat(first.day()).isEqualTo(2);
        assertThat(first.startPeriod()).isEqualTo(1);
        assertThat(first.roomId()).isEqualTo(11L);
        assertThat(validator.validate(p, out.placements()).violations()).isEmpty();
    }

    // ------------------------------------------------------------------ 场景十

    @Test
    void softConstraintWeightChangesTheObjectiveAndTheResult() {
        SchedulingProblem preferred = base()
                .rooms(List.of(room(11, "A101", 60)))
                .teachers(List.of(teacher(1, "张老师")))
                .classes(List.of(new ClassDef(101, "软件2401", 40, null, null)))
                .teachingClasses(List.of(tc(201, 101, 40)))
                .tasks(List.of(task(301, "Java", 201, List.of(1L), 1, 2)))
                .rules(List.of(new TimeRule(1, RuleTarget.TEACHER, 1L, TimeRuleKind.PREFERRED,
                        4, 1, 8, Weeks.all(16), 3)))
                .build();
        SchedulingSolver.Outcome out = solve(preferred);
        assertThat(out.status()).isEqualTo(SolverStatus.SUCCEEDED);
        assertThat(out.placements().get(0).day()).isEqualTo(4);
        SchedulingValidator.Report report = validator.validate(preferred, out.placements());
        SchedulingEvaluator.Report evaluation = evaluator.evaluate(preferred, out.placements(), report);
        SchedulingEvaluator.SoftScore preference = evaluation.details().stream()
                .filter(d -> d.code() == ConstraintCode.TEACHER_PREFERENCE).findFirst().orElseThrow();
        assertThat(preference.satisfaction()).isEqualTo(1.0);
        // 其余软约束（教室空座率等）仍可能扣分，因此总分不要求满分
        assertThat(evaluation.score()).isBetween(80.0, 100.0);
        assertThat(evaluation.hardViolations()).isZero();

        SchedulingProblem avoided = base()
                .rooms(List.of(room(11, "A101", 60)))
                .teachers(List.of(teacher(1, "张老师")))
                .classes(List.of(new ClassDef(101, "软件2401", 40, null, null)))
                .teachingClasses(List.of(tc(201, 101, 40)))
                .tasks(List.of(task(301, "Java", 201, List.of(1L), 1, 2)))
                .rules(List.of(new TimeRule(1, RuleTarget.TEACHER, 1L, TimeRuleKind.AVOID,
                        1, 1, 8, Weeks.all(16), 5)))
                .build();
        SchedulingSolver.Outcome out2 = solve(avoided);
        assertThat(out2.status()).isEqualTo(SolverStatus.SUCCEEDED);
        assertThat(out2.placements().get(0).day()).isNotEqualTo(1);
        assertThat(out2.objective()).isLessThan(out.objective() + 1000);
    }

    // ------------------------------------------------------------------ 场景十一

    @Test
    void contradictoryFixedAndUnavailableRulesAreReportedAsInfeasibleWithDiagnostics() {
        SchedulingProblem p = base()
                .rooms(List.of(room(11, "A101", 60)))
                .teachers(List.of(teacher(1, "张老师")))
                .classes(List.of(new ClassDef(101, "软件2401", 40, null, null)))
                .teachingClasses(List.of(tc(201, 101, 40)))
                .tasks(List.of(task(301, "Java", 201, List.of(1L), 1, 2)))
                .rules(List.of(
                        fixed(RuleTarget.TASK, 301L, 2, 1, 4),
                        forbidden(RuleTarget.TEACHER, 1L, 2, 1, 4)))
                .build();

        List<com.campus.platform.scheduling.solver.model.Issue> precheck = SchedulingPrecheck.check(p);
        assertThat(precheck).anyMatch(i -> i.code().equals("NO_CANDIDATE"));

        SchedulingSolver.Outcome out = solver.solve(p,
                new SolverParams(10, 1, 1, false, false), new SchedulingSolver.Handle(), null);
        assertThat(out.status()).isIn(SolverStatus.INFEASIBLE, SolverStatus.PARTIAL);
        assertThat(SchedulingPrecheck.infeasibleHints(p)).anyMatch(
                i -> i.code().equals("RESOURCE_SUMMARY") || i.severity()
                        == com.campus.platform.scheduling.solver.model.Issue.Severity.ERROR);
    }

    // ------------------------------------------------------------------ 场景十二

    @Test
    void cancelledSolveStopsAndReportsCancelled() {
        SchedulingProblem p = base()
                .rooms(List.of(room(11, "A101", 60), room(12, "A102", 60)))
                .teachers(List.of(teacher(1, "张老师")))
                .classes(List.of(new ClassDef(101, "软件2401", 40, null, null)))
                .teachingClasses(List.of(tc(201, 101, 40)))
                .tasks(List.of(task(301, "Java", 201, List.of(1L), 2, 2)))
                .build();
        SchedulingSolver.Handle handle = new SchedulingSolver.Handle();
        handle.cancel();
        SchedulingSolver.Outcome out = solver.solve(p, new SolverParams(5, 1, 1, true, false), handle, null);
        assertThat(out.status()).isEqualTo(SolverStatus.CANCELLED);
    }

    // ------------------------------------------------------------------ helpers

    private SchedulingSolver.Outcome solve(SchedulingProblem p) {
        return solver.solve(p, new SolverParams(20, 1, 1, true, false), new SchedulingSolver.Handle(), null);
    }

    private static SchedulingProblem.Builder base() {
        return SchedulingProblem.builder()
                .teachingWeeks(16)
                .teachingDays(DAYS)
                .periods(periods())
                .defaultTravelMinutes(30);
    }

    static List<PeriodDef> periods() {
        List<PeriodDef> list = new ArrayList<>();
        int[] starts = {480, 530, 580, 630, 810, 860, 910, 960};
        for (int i = 0; i < starts.length; i++) {
            list.add(new PeriodDef(i + 1, i < 4 ? "MORNING" : "AFTERNOON", starts[i], starts[i] + 45));
        }
        return list;
    }

    private static RoomDef room(long id, String code, int capacity) {
        return new RoomDef(id, code, 1L, capacity, "NORMAL", Set.of());
    }

    private static TeacherDef teacher(long id, String name) {
        return new TeacherDef(id, name, null, null, null, null);
    }

    private static TeachingClassDef tc(long id, long classId, int students) {
        return new TeachingClassDef(id, "教学班" + id, students,
                List.of(new TeachingClassDef.Member(classId, true)));
    }

    static TaskDef task(long id, String name, long tcId, List<Long> teachers, int sessions, int len) {
        return new TaskDef(id, "T" + id, name, 400 + id, tcId, teachers, Weeks.all(16), sessions, len,
                sessions * len * 16, true, null, Set.of(), 5, false, false,
                Set.of(), Set.of(), Set.of());
    }

    private static TimeRule forbidden(RuleTarget target, long targetId, int day, int start, int end) {
        return new TimeRule(1, target, targetId, TimeRuleKind.FORBIDDEN, day, start, end, Weeks.all(16), 1);
    }

    private static TimeRule fixed(RuleTarget target, long targetId, int day, int start, int end) {
        return new TimeRule(2, target, targetId, TimeRuleKind.FIXED, day, start, end, Weeks.all(16), 1);
    }

    /** 两任务在时间上重叠的节数（用于断言教师/教室不冲突）。 */
    static int overlaps(List<Placement> placements, long taskA, long taskB) {
        int count = 0;
        for (Placement a : placements) {
            if (a.taskId() != taskA || !a.placed()) {
                continue;
            }
            for (Placement b : placements) {
                if (b.taskId() != taskB || !b.placed()) {
                    continue;
                }
                if (a.day().equals(b.day()) && a.startPeriod().equals(b.startPeriod())) {
                    count++;
                }
            }
        }
        return count;
    }
}
