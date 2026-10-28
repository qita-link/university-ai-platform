package com.campus.platform.user.datascope;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.campus.platform.user.entity.SysUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DataScopeTest {

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUser.class);
    }

    @Test
    void noneRejectsEveryOrgIncludingNull() {
        DataScope scope = DataScope.none();
        assertThat(scope.unrestricted()).isFalse();
        assertThat(scope.isEmpty()).isTrue();
        assertThat(scope.allows(1L)).isFalse();
        assertThat(scope.allows(null)).isFalse();
    }

    @Test
    void allAllowsEveryOrgIncludingUnassignedData() {
        DataScope scope = DataScope.all();
        assertThat(scope.unrestricted()).isTrue();
        assertThat(scope.isEmpty()).isFalse();
        assertThat(scope.allows(1L)).isTrue();
        assertThat(scope.allows(null)).isTrue();
    }

    @Test
    void ofKeepsOnlyTheGivenOrgsAndTreatsEmptyAsNone() {
        DataScope scope = DataScope.of(Set.of(1L, 2L));
        assertThat(scope.allows(1L)).isTrue();
        assertThat(scope.allows(3L)).isFalse();
        assertThat(scope.allows(null)).isFalse();
        assertThat(scope.isEmpty()).isFalse();

        assertThat(DataScope.of(Set.of()).isEmpty()).isTrue();
        assertThat(DataScope.of(null).isEmpty()).isTrue();
    }

    @Test
    void applyToAddsAnImpossibleConditionForEmptyScope() {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        DataScope.none().applyTo(wrapper, SysUser::getOrgUnitId);
        assertThat(wrapper.getSqlSegment()).contains("1 = 0");
    }

    @Test
    void applyToAddsInConditionAndSkipsItForAllScope() {
        LambdaQueryWrapper<SysUser> restricted = new LambdaQueryWrapper<>();
        DataScope.of(Set.of(7L, 8L)).applyTo(restricted, SysUser::getOrgUnitId);
        assertThat(restricted.getSqlSegment()).contains("org_unit_id IN");

        LambdaQueryWrapper<SysUser> unrestricted = new LambdaQueryWrapper<>();
        DataScope.all().applyTo(unrestricted, SysUser::getOrgUnitId);
        assertThat(unrestricted.getSqlSegment()).isEmpty();
    }
}
