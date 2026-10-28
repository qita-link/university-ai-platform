package com.campus.platform.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * MyBatis-Plus 配置。
 * <p>
 * Mapper 接口统一使用 {@code @Mapper} 注解，由 starter 在启动类所在包下自动扫描。
 */
@Configuration
@EnableTransactionManagement
public class MybatisPlusConfig {

    /** 单页最大条数，与 PageQuery.MAX_SIZE 保持一致，作为服务端兜底 */
    private static final long MAX_PAGE_LIMIT = 100L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 乐观锁：实体中 @Version 字段
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        // 防止全表 update/delete
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        // 分页：放在最后
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(MAX_PAGE_LIMIT);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
