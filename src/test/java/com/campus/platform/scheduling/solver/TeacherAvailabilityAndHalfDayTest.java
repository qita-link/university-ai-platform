package com.campus.platform.scheduling.solver;

import com.campus.platform.scheduling.solver.model.ClassDef;
import com.campus.platform.scheduling.solver.model.ConstraintCode;
import com.campus.platform.scheduling.solver.model.PeriodDef;
import com.campus.platform.scheduling.solver.model.Placement;
import com.campus.platform.scheduling.solver.model.RoomDef;
import com.campus.platform.scheduling.solver.model.RuleTarget;
import com.campus.platform.scheduling.solver.model.SchedulingProblem;
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
 * 外聘教师「可来时间窗」与「半天集中」的行为测试。
 * <p>
 * 刻意不经过 CP-SAT：这两个能力的核心是规则判定与评分口径，
 * 用纯 Java 断言就能覆盖，也不必依赖 OR-Tools 原生库。
 */
class TeacherAvailabilityAndHalfDayTest {

    private final SchedulingValidator validator = new SchedulingValidator();
    private final SchedulingEvaluator evaluator = new SchedulingEvaluator();

    private static final Set<Integer> DAYS = Set.of(1, 2, 3, 4, 5);

    // ------------------------------------------------------------------ 可来时间窗

    @Test
    void availabilityWindowForbidsSlotsOutsideIt() {
        // 教师 1 只能在周三上午来（第 1-4 节）
        SchedulingProblem p = base()
                .rules(List.of(available(RuleTarget.TEACHER, 1L, 3, 1, 4)))
                .build();
        TaskRules rules = TaskRules.of(p, p.tasks().get(0));

        // 周三上午：允许
        assertThat(rules.forbiddenBy(3, 1, 2)).isNull();
        assertThat(rules.forbiddenBy(3, 3, 4)).isNull();
        // 周四：整天不行
        assertThat(rules.forbiddenBy(4, 1, 2)).isEqualTo(ConstraintCode.TEACHER_AVAILABILITY);
        // 周三下午：同一可他来的那天，但不在时间窗内
        assertThat(rules.forbiddenBy(3, 5, 6)).isEqualTo(ConstraintCode.TEACHER_AVAILABILITY);
    }

    @Test
    void availabilityIsHardEvenWithSeveralWindows() {
        // 周三上午 + 周五下午都可以
        SchedulingProblem p = base()
                .rules(List.of(
                        available(RuleTarget.TEACHER, 1L, 3, 1, 4),
                        available(RuleTarget.TEACHER, 1L, 5, 5, 8)))
                .build();
        TaskRules rules = TaskRules.of(p, p.tasks().get(0));

        assertThat(rules.forbiddenBy(3, 1, 2)).isNull();
        assertThat(rules.forbiddenBy(5, 5, 6)).isNull();
        assertThat(rules.forbiddenBy(5, 1, 2)).isEqualTo(ConstraintCode.TEACHER_AVAILABILITY);
        assertThat(rules.forbiddenBy(2, 1, 2)).isEqualTo(ConstraintCode.TEACHER_AVAILABILITY);
    }

    @Test
    void forbiddenStillAppliesTogetherWithAvailability() {
        // 可来周三上午，但周三第 1 节又被禁排：两者取交集
        SchedulingProblem p = base()
                .rules(List.of(
                        available(RuleTarget.TEACHER, 1L, 3, 1, 4),
                        new TimeRule(9, RuleTarget.TEACHER, 1L, TimeRuleKind.FORBIDDEN, 3, 1, 1,
                                Weeks.all(16), 1)))
                .build();
        TaskRules rules = TaskRules.of(p, p.tasks().get(0));

        assertThat(rules.forbiddenBy(3, 1, 1)).isEqualTo(ConstraintCode.TEACHER_UNAVAILABLE);
        assertThat(rules.forbiddenBy(3, 2, 3)).isNull();
    }

    @Test
    void otherTeachersAreNotAffected() {
        // 只有教师 2 有可来时间窗，教师 1 不受影响
        SchedulingProblem p = base()
                .rules(List.of(available(RuleTarget.TEACHER, 2L, 3, 1, 4)))
                .build();
        TaskRules rules = TaskRules.of(p, p.tasks().get(0));

        assertThat(rules.forbiddenBy(4, 1, 2)).isNull();
    }

    // ------------------------------------------------------------------ 半天集中

    @Test
    void halfDayPenaltyCountsDistinctDaySections() {
        // 同一位教师：周一上午 + 周一下午 + 周二上午 = 占用 3 个「星期 + 半天」
        List<Placement> placements = List.of(
                new Placement(301, 1, 1, 1, 11L, false),
                new Placement(301, 2, 1, 5, 11L, false),
                new Placement(302, 1, 2, 1, 11L, false));
        SchedulingProblem p = base().build();

        SchedulingEvaluator.Report report = evaluator.evaluate(p, placements, validator.validate(p, placements));
        SchedulingEvaluator.SoftScore score = report.details().stream()
                .filter(d -> d.code() == ConstraintCode.TEACHER_HALF_DAY)
                .findFirst()
                .orElseThrow();

        assertThat(score.applicable()).isEqualTo(3);
        assertThat(score.violated()).isEqualTo(2);
        assertThat(score.penalty()).isEqualTo(2L * score.weight());
    }

    @Test
    void halfDayPenaltyIsZeroWhenEverythingInOneHalf() {
        // 周一上午两节课：只占 1 个半天
        List<Placement> placements = List.of(
                new Placement(301, 1, 1, 1, 11L, false),
                new Placement(302, 1, 1, 3, 11L, false));
        SchedulingProblem p = base().build();

        SchedulingEvaluator.Report report = evaluator.evaluate(p, placements, validator.validate(p, placements));
        SchedulingEvaluator.SoftScore score = report.details().stream()
                .filter(d -> d.code() == ConstraintCode.TEACHER_HALF_DAY)
                .findFirst()
                .orElseThrow();

        assertThat(score.applicable()).isEqualTo(1);
        assertThat(score.violated()).isZero();
        assertThat(score.penalty()).isZero();
    }

    // ------------------------------------------------------------------ 构造

    private static SchedulingProblem.Builder base() {
        return SchedulingProblem.builder()
                .teachingWeeks(16)
                .teachingDays(DAYS)
                .periods(periods())
                .defaultTravelMinutes(30)
                .rooms(List.of(new RoomDef(11L, "A101", 1L, 60, "NORMAL", Set.of())))
                .teachers(List.of(
                        new TeacherDef(1L, "张老师", null, null, null, null),
                        new TeacherDef(2L, "李老师", null, null, null, null)))
                .classes(List.of(new ClassDef(101L, "软件2401", 40, null, null)))
                .teachingClasses(List.of(new TeachingClassDef(201L, "教学班201", 40,
                        List.of(new TeachingClassDef.Member(101L, true)))))
                .tasks(List.of(
                        task(301, List.of(1L)),
                        task(302, List.of(1L)),
                        task(303, List.of(2L))));
    }

    /** 周一到周五，每天 8 节：1-4 上午、5-8 下午。 */
    private static List<PeriodDef> periods() {
        List<PeriodDef> list = new ArrayList<>();
        int[] starts = {480, 530, 580, 630, 810, 860, 910, 960};
        for (int i = 0; i < starts.length; i++) {
            list.add(new PeriodDef(i + 1, i < 4 ? "MORNING" : "AFTERNOON", starts[i], starts[i] + 45));
        }
        return list;
    }

    private static TaskDef task(long id, List<Long> teachers) {
        return new TaskDef(id, "T" + id, "课程" + id, 400 + id, 201L, teachers, Weeks.all(16), 1, 2,
                32, true, null, Set.of(), 5, false, false, Set.of(), Set.of(), Set.of());
    }

    private static TimeRule available(RuleTarget target, long targetId, int day, int start, int end) {
        return new TimeRule(7, target, targetId, TimeRuleKind.AVAILABLE, day, start, end, Weeks.all(16), 1);
    }
}
