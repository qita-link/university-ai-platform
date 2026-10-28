package com.campus.platform.course.service;

import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.course.entity.Course;
import com.campus.platform.course.entity.CourseTeacher;
import com.campus.platform.course.entity.TeacherRole;
import com.campus.platform.course.mapper.CourseTeacherMapper;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 课程数据归属判断，REST Controller 与阶段七 AI 工具共用。
 * <ul>
 *   <li>{@code course:manage_all}（管理员）：可管理全部课程；</li>
 *   <li>{@code course:manage_own}（教师）：只能管理自己是主讲的课程；</li>
 *   <li>任课教师（主讲或助教）：可查看名单、审批请假，但不能改课程资料与排课；</li>
 *   <li>其他登录用户：只读（已停开课程对外表现为 404）。</li>
 * </ul>
 */
@Service
public class CourseAccessService {

    private final CourseTeacherMapper teacherMapper;

    public CourseAccessService(CourseTeacherMapper teacherMapper) {
        this.teacherMapper = teacherMapper;
    }

    public boolean isAdmin(AuthenticatedUser user) {
        return user != null && user.hasPermission(Permissions.COURSE_MANAGE_ALL);
    }

    public boolean isTeacherOf(AuthenticatedUser user, long courseId) {
        return user != null && user.userId() != null
                && teacherMapper.selectOne(courseId, user.userId()) != null;
    }

    public boolean isPrimaryTeacher(AuthenticatedUser user, long courseId) {
        if (user == null || user.userId() == null) {
            return false;
        }
        CourseTeacher relation = teacherMapper.selectOne(courseId, user.userId());
        return relation != null && relation.getTeacherRole() == TeacherRole.PRIMARY;
    }

    /** 能否修改课程资料、状态与排课。 */
    public boolean canManage(AuthenticatedUser user, Course course) {
        if (user == null || course == null) {
            return false;
        }
        if (isAdmin(user)) {
            return true;
        }
        return user.hasPermission(Permissions.COURSE_MANAGE_OWN) && isPrimaryTeacher(user, course.getId());
    }

    public void requireManageable(AuthenticatedUser user, Course course) {
        if (!canManage(user, course)) {
            throw BusinessException.forbidden("只有主讲教师或管理员可以修改该课程");
        }
    }

    /** 能否查看名单、审批该课程的请假：任课教师或管理员。 */
    public boolean canViewRoster(AuthenticatedUser user, long courseId) {
        if (user == null) {
            return false;
        }
        return isAdmin(user) || isTeacherOf(user, courseId);
    }

    public void requireRosterViewable(AuthenticatedUser user, long courseId) {
        if (!canViewRoster(user, courseId)) {
            throw BusinessException.forbidden("只有任课教师或管理员可以查看选课名单");
        }
    }

    /** 能否审批该课程的请假。 */
    public boolean canReviewLeave(AuthenticatedUser user, long courseId) {
        if (user == null) {
            return false;
        }
        if (user.hasPermission(Permissions.LEAVE_MANAGE_ALL)) {
            return true;
        }
        return user.hasPermission(Permissions.LEAVE_REVIEW) && isTeacherOf(user, courseId);
    }

    /** 是否可以看到已停开课程（停开对普通用户表现为不存在）。 */
    public boolean canSeeCancelled(AuthenticatedUser user, Course course) {
        return isAdmin(user) || (course != null && isTeacherOf(user, course.getId()));
    }

    public List<Long> teachingCourseIds(long teacherId) {
        return teacherMapper.selectCourseIdsByTeachers(List.of(teacherId));
    }

    public List<Long> teacherIdsOf(long courseId) {
        return teacherMapper.selectByCourse(courseId).stream()
                .map(CourseTeacher::getTeacherId)
                .filter(Objects::nonNull)
                .toList();
    }
}
