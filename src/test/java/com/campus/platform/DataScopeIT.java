package com.campus.platform;

import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Roles;
import com.campus.platform.support.IntegrationTestBase;
import com.campus.platform.user.datascope.DataScope;
import com.campus.platform.user.datascope.DataScopeService;
import com.campus.platform.user.entity.OrgType;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段二：DataScopeService 的组织范围计算（真实 MySQL 递归查询）。
 */
class DataScopeIT extends IntegrationTestBase {

    @Autowired
    DataScopeService dataScopeService;

    @Test
    void descendantsAndAncestorsFollowTheConfiguredHierarchy() {
        SysOrgUnit college = createOrg(null, OrgType.COLLEGE);
        SysOrgUnit department = createOrg(college.getId(), OrgType.DEPARTMENT);
        SysOrgUnit major = createOrg(department.getId(), OrgType.MAJOR);
        SysOrgUnit clazz = createOrg(major.getId(), OrgType.CLASS);

        assertThat(dataScopeService.orgAndDescendants(college.getId()))
                .containsExactlyInAnyOrder(college.getId(), department.getId(), major.getId(), clazz.getId());
        assertThat(dataScopeService.orgAndDescendants(major.getId()))
                .containsExactlyInAnyOrder(major.getId(), clazz.getId());
        assertThat(dataScopeService.orgAndDescendants(clazz.getId())).containsExactly(clazz.getId());
        assertThat(dataScopeService.orgAndDescendants(null)).isEmpty();
        assertThat(dataScopeService.orgAndDescendants(99999999L)).isEmpty();

        assertThat(dataScopeService.orgAndAncestors(clazz.getId()))
                .containsExactlyInAnyOrder(clazz.getId(), major.getId(), department.getId(), college.getId());
        assertThat(dataScopeService.orgAndAncestors(college.getId())).containsExactly(college.getId());
        assertThat(dataScopeService.orgAndAncestors(null)).isEmpty();
        assertThat(dataScopeService.orgAndAncestors(99999999L)).isEmpty();
    }

    @Test
    void manageableScopeIsLimitedToOwnOrgAndDescendantsWithoutGlobalPermission() {
        SysOrgUnit college = createOrg(null, OrgType.COLLEGE);
        SysOrgUnit department = createOrg(college.getId(), OrgType.DEPARTMENT);
        SysOrgUnit major = createOrg(department.getId(), OrgType.MAJOR);
        SysOrgUnit clazz = createOrg(major.getId(), OrgType.CLASS);
        SysOrgUnit otherCollege = createOrg(null, OrgType.COLLEGE);

        SysUser student = createUserInOrg("scope_stu", major.getId(), Roles.STUDENT);
        AuthenticatedUser principal = principalOf(student);
        DataScope scope = dataScopeService.manageableScope(principal, "course:manage_all");

        assertThat(scope.unrestricted()).isFalse();
        assertThat(scope.orgIds()).containsExactlyInAnyOrder(major.getId(), clazz.getId());
        assertThat(scope.allows(major.getId())).isTrue();
        assertThat(scope.allows(clazz.getId())).isTrue();
        assertThat(scope.allows(college.getId())).isFalse();
        assertThat(scope.allows(otherCollege.getId())).isFalse();
        assertThat(scope.allows(null)).isFalse();

        // 拥有全局权限点时才不受组织限制；仅有普通角色权限不够
        AuthenticatedUser globalReader = new AuthenticatedUser(student.getId(), student.getUsername(), "g",
                major.getId(), Set.of(Roles.STUDENT), Set.of("course:manage_all"));
        assertThat(dataScopeService.manageableScope(globalReader, "course:manage_all").unrestricted()).isTrue();
        assertThat(dataScopeService.manageableScope(principal, "course:manage_all").unrestricted()).isFalse();

        SysUser unassigned = createUser("scope_none", Roles.STUDENT);
        assertThat(dataScopeService.manageableScope(principalOf(unassigned), null).isEmpty()).isTrue();
        assertThat(dataScopeService.manageableScope(null, null).isEmpty()).isTrue();
    }

    @Test
    void membershipIncludesSelfAndEveryAncestor() {
        SysOrgUnit college = createOrg(null, OrgType.COLLEGE);
        SysOrgUnit department = createOrg(college.getId(), OrgType.DEPARTMENT);
        SysOrgUnit major = createOrg(department.getId(), OrgType.MAJOR);
        SysOrgUnit clazz = createOrg(major.getId(), OrgType.CLASS);
        SysOrgUnit siblingMajor = createOrg(department.getId(), OrgType.MAJOR);

        SysUser student = createUserInOrg("member_stu", clazz.getId(), Roles.STUDENT);
        AuthenticatedUser principal = principalOf(student);

        assertThat(dataScopeService.membershipOrgIds(principal))
                .containsExactlyInAnyOrder(clazz.getId(), major.getId(), department.getId(), college.getId());
        assertThat(dataScopeService.isMemberOf(principal, college.getId())).isTrue();
        assertThat(dataScopeService.isMemberOf(principal, clazz.getId())).isTrue();
        assertThat(dataScopeService.isMemberOf(principal, siblingMajor.getId())).isFalse();
        assertThat(dataScopeService.isMemberOf(principal, null)).isFalse();
        assertThat(dataScopeService.membershipOrgIds(null)).isEmpty();
    }

    @Test
    void requireManageableRejectsSiblingOrParentOrgAndAcceptsGlobalScope() {
        SysOrgUnit college = createOrg(null, OrgType.COLLEGE);
        SysOrgUnit department = createOrg(college.getId(), OrgType.DEPARTMENT);
        SysOrgUnit major = createOrg(department.getId(), OrgType.MAJOR);
        SysOrgUnit sibling = createOrg(college.getId(), OrgType.DEPARTMENT);

        SysUser student = createUserInOrg("require_stu", major.getId(), Roles.STUDENT);
        AuthenticatedUser principal = principalOf(student);

        // 本组织及下级可管理
        dataScopeService.requireManageable(principal, major.getId(), null);

        assertThatThrownBy(() -> dataScopeService.requireManageable(principal, college.getId(), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> dataScopeService.requireManageable(principal, sibling.getId(), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        // 持有全局权限点后可以管理任意组织，包括未归属组织的资源
        AuthenticatedUser admin = principalOf(createUser("require_adm", Roles.ADMIN));
        dataScopeService.requireManageable(admin, college.getId(), "course:manage_all");
        dataScopeService.requireManageable(admin, null, "course:manage_all");
    }
}
