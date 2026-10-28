package com.campus.platform.course.service;

import com.campus.platform.course.entity.CourseSchedule;
import com.campus.platform.course.entity.WeekParity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 阶段六：排课时间冲突判定（节次区间、周次区间、单双周）。 */
class ScheduleConflictTest {

    private static CourseSchedule slot(int day, int startPeriod, int endPeriod, int startWeek, int endWeek,
                                       WeekParity parity) {
        CourseSchedule s = new CourseSchedule();
        s.setCourseId(1L);
        s.setDayOfWeek(day);
        s.setStartPeriod(startPeriod);
        s.setEndPeriod(endPeriod);
        s.setStartWeek(startWeek);
        s.setEndWeek(endWeek);
        s.setWeekParity(parity);
        return s;
    }

    @Test
    void identicalSlotsConflict() {
        CourseSchedule a = slot(1, 1, 2, 1, 16, WeekParity.ALL);
        CourseSchedule b = slot(1, 1, 2, 1, 16, WeekParity.ALL);
        assertThat(ScheduleConflict.overlaps(a, b)).isTrue();
    }

    @Test
    void differentDayNeverConflicts() {
        CourseSchedule a = slot(1, 1, 2, 1, 16, WeekParity.ALL);
        CourseSchedule b = slot(2, 1, 2, 1, 16, WeekParity.ALL);
        assertThat(ScheduleConflict.overlaps(a, b)).isFalse();
    }

    @Test
    void adjacentPeriodsDoNotConflictButTouchingOnesDo() {
        CourseSchedule first = slot(3, 1, 2, 1, 16, WeekParity.ALL);
        assertThat(ScheduleConflict.overlaps(first, slot(3, 3, 4, 1, 16, WeekParity.ALL))).isFalse();
        assertThat(ScheduleConflict.overlaps(first, slot(3, 2, 3, 1, 16, WeekParity.ALL))).isTrue();
        assertThat(ScheduleConflict.overlaps(first, slot(3, 1, 4, 1, 16, WeekParity.ALL))).isTrue();
    }

    @Test
    void disjointWeeksDoNotConflict() {
        CourseSchedule early = slot(4, 1, 2, 1, 5, WeekParity.ALL);
        assertThat(ScheduleConflict.overlaps(early, slot(4, 1, 2, 6, 10, WeekParity.ALL))).isFalse();
        assertThat(ScheduleConflict.overlaps(early, slot(4, 1, 2, 5, 8, WeekParity.ALL))).isTrue();
    }

    @Test
    void oddAndEvenParityDoNotConflict() {
        CourseSchedule odd = slot(5, 1, 2, 1, 16, WeekParity.ODD);
        assertThat(ScheduleConflict.overlaps(odd, slot(5, 1, 2, 1, 16, WeekParity.EVEN))).isFalse();
        assertThat(ScheduleConflict.overlaps(odd, slot(5, 1, 2, 1, 16, WeekParity.ODD))).isTrue();
    }

    @Test
    void allParityConflictsWithAnyParity() {
        CourseSchedule all = slot(6, 1, 2, 1, 16, WeekParity.ALL);
        assertThat(ScheduleConflict.overlaps(all, slot(6, 1, 2, 1, 16, WeekParity.ODD))).isTrue();
        assertThat(ScheduleConflict.overlaps(all, slot(6, 1, 2, 1, 16, WeekParity.EVEN))).isTrue();
    }

    @Test
    void nullParityIsTreatedAsAll() {
        CourseSchedule noParity = slot(7, 5, 6, 1, 8, null);
        assertThat(ScheduleConflict.overlaps(noParity, slot(7, 5, 6, 1, 8, WeekParity.EVEN))).isTrue();
        assertThat(ScheduleConflict.parityCompatible(null, WeekParity.ODD)).isTrue();
    }

    @Test
    void firstConflictReturnsTheOverlappingSlot() {
        CourseSchedule candidate = slot(1, 1, 2, 1, 16, WeekParity.ALL);
        CourseSchedule otherDay = slot(2, 1, 2, 1, 16, WeekParity.ALL);
        CourseSchedule overlapping = slot(1, 2, 3, 1, 16, WeekParity.ALL);

        assertThat(ScheduleConflict.firstConflict(candidate, List.of(otherDay, overlapping)))
                .containsSame(overlapping);
        assertThat(ScheduleConflict.firstConflict(candidate, List.of(otherDay))).isEmpty();
        assertThat(ScheduleConflict.firstConflict(candidate, List.of())).isEmpty();
    }

    @Test
    void nullSlotsAreNotConflicts() {
        CourseSchedule slot = slot(1, 1, 2, 1, 16, WeekParity.ALL);
        assertThat(ScheduleConflict.overlaps(null, slot)).isFalse();
        assertThat(ScheduleConflict.overlaps(slot, null)).isFalse();
        assertThat(ScheduleConflict.periodsOverlap(null, 2, 1, 2)).isFalse();
    }

    @Test
    void sameWeekHonoursParity() {
        CourseSchedule odd = slot(1, 1, 2, 1, 16, WeekParity.ODD);
        assertThat(odd.sameWeek(1)).isTrue();
        assertThat(odd.sameWeek(2)).isFalse();
        assertThat(odd.sameWeek(17)).isFalse();

        CourseSchedule even = slot(1, 1, 2, 1, 16, WeekParity.EVEN);
        assertThat(even.sameWeek(2)).isTrue();
        assertThat(even.sameWeek(3)).isFalse();
    }
}
