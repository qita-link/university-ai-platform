package com.campus.platform.course.service;

import com.campus.platform.course.mapper.CourseSelectionMapper;
import com.campus.platform.course.mapper.CourseTeacherMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 课程成员判定：任课教师 + 有效选课学生。
 * <p>
 * 供知识库 {@code visibility=COURSE} 的可见范围使用（阶段六打通阶段三的遗留项）。
 * 只依赖课程模块自身的 Mapper，避免知识库与课程模块出现循环依赖。
 */
@Service
public class CourseMembershipService {

    private final CourseTeacherMapper teacherMapper;
    private final CourseSelectionMapper selectionMapper;

    public CourseMembershipService(CourseTeacherMapper teacherMapper, CourseSelectionMapper selectionMapper) {
        this.teacherMapper = teacherMapper;
        this.selectionMapper = selectionMapper;
    }

    public boolean isTeacher(Long userId, Long courseId) {
        if (userId == null || courseId == null) {
            return false;
        }
        return teacherMapper.selectOne(courseId, userId) != null;
    }

    public boolean isSelected(Long userId, Long courseId) {
        if (userId == null || courseId == null) {
            return false;
        }
        return selectionMapper.selectActive(courseId, userId) != null;
    }

    public boolean isMember(Long userId, Long courseId) {
        return isTeacher(userId, courseId) || isSelected(userId, courseId);
    }

    /** 用户作为任课教师或已选学生关联的全部课程 ID。 */
    public Set<Long> memberCourseIds(Long userId) {
        Set<Long> ids = new LinkedHashSet<>();
        if (userId == null) {
            return ids;
        }
        List<Long> teaching = teacherMapper.selectCourseIdsByTeachers(List.of(userId));
        if (teaching != null) {
            ids.addAll(teaching);
        }
        List<Long> selected = selectionMapper.selectActiveCourseIds(userId);
        if (selected != null) {
            ids.addAll(selected);
        }
        return ids;
    }
}
