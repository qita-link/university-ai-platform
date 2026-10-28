package com.campus.platform.user.service;

import com.campus.platform.user.dto.OrgTreeNode;
import com.campus.platform.user.entity.OrgStatus;
import com.campus.platform.user.entity.OrgType;
import com.campus.platform.user.entity.SysOrgUnit;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrgTreeUtilTest {

    private static SysOrgUnit org(long id, Long parent, OrgType type, int sort) {
        SysOrgUnit o = new SysOrgUnit();
        o.setId(id);
        o.setParentId(parent);
        o.setCode("C" + id);
        o.setName("N" + id);
        o.setType(type);
        o.setStatus(OrgStatus.ACTIVE);
        o.setSortOrder(sort);
        return o;
    }

    @Test
    void buildsSortedTreeAndPromotesOrphansToRoots() {
        List<SysOrgUnit> list = List.of(
                org(1, null, OrgType.COLLEGE, 2),
                org(2, null, OrgType.COLLEGE, 1),
                org(3, 1L, OrgType.MAJOR, 0),
                org(4, 3L, OrgType.CLASS, 0),
                // 上级 99 不在列表中（例如已停用而被过滤）
                org(5, 99L, OrgType.CLASS, 0));
        List<OrgTreeNode> roots = OrgUnitService.buildTree(list);
        assertThat(roots).extracting(OrgTreeNode::id).containsExactly(5L, 2L, 1L);
        OrgTreeNode college1 = roots.get(2);
        assertThat(college1.children()).extracting(OrgTreeNode::id).containsExactly(3L);
        assertThat(college1.children().get(0).children()).extracting(OrgTreeNode::id).containsExactly(4L);
    }

    @Test
    void descendantsTerminateEvenOnCorruptedCycle() {
        Map<Long, SysOrgUnit> tree = new HashMap<>();
        tree.put(1L, org(1, 3L, OrgType.COLLEGE, 0)); // 脏数据：1 -> 3 -> 2 -> 1
        tree.put(2L, org(2, 1L, OrgType.MAJOR, 0));
        tree.put(3L, org(3, 2L, OrgType.CLASS, 0));
        assertThat(OrgUnitService.descendantsOf(tree, 1L)).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void typeHierarchyAllowsSkippingLevelsButNotSameOrHigher() {
        assertThat(OrgType.COLLEGE.canContain(OrgType.MAJOR)).isTrue();
        assertThat(OrgType.MAJOR.canContain(OrgType.CLASS)).isTrue();
        assertThat(OrgType.CLASS.canContain(OrgType.CLASS)).isFalse();
        assertThat(OrgType.MAJOR.canContain(OrgType.COLLEGE)).isFalse();
    }

    @Test
    void escapeLikeNeutralisesWildcards() {
        assertThat(UserAdminService.escapeLike("a%b_c\\")).isEqualTo("a\\%b\\_c\\\\");
    }
}
