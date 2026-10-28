package com.campus.platform.knowledge.importer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.knowledge.config.KnowledgeImportProperties;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.entity.UserStatus;
import com.campus.platform.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

/**
 * 可选的本地语料启动导入器。
 * <p>
 * 仅在显式配置 app.knowledge.import.enabled=true 且提供目录时运行；
 * 在生产环境保持关闭，避免服务器启动时扫描任意目录。
 */
@Slf4j
@Component
@Order(100)
@ConditionalOnProperty(prefix = "app.knowledge.import", name = "enabled", havingValue = "true")
public class KnowledgeImportRunner implements ApplicationRunner {

    private final KnowledgeImportProperties properties;
    private final KnowledgeImportService importService;
    private final SysUserMapper userMapper;

    public KnowledgeImportRunner(KnowledgeImportProperties properties, KnowledgeImportService importService,
                                 SysUserMapper userMapper) {
        this.properties = properties;
        this.importService = importService;
        this.userMapper = userMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.configured()) {
            log.warn("app.knowledge.import.enabled=true 但未配置 app.knowledge.import.dir，跳过语料导入");
            return;
        }
        SysUser owner = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, properties.ownerUsername()));
        if (owner == null || owner.getStatus() != UserStatus.ACTIVE) {
            log.warn("语料导入用户不存在或未启用: {}", properties.ownerUsername());
            return;
        }
        Path root = Path.of(properties.dir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            log.warn("语料目录不存在，跳过导入: {}", root);
            return;
        }
        AuthenticatedUser principal = new AuthenticatedUser(owner.getId(), owner.getUsername(),
                owner.getDisplayName(), owner.getOrgUnitId(),
                new HashSet<>(userMapper.selectRoleCodes(owner.getId())),
                new HashSet<>(userMapper.selectPermissionCodes(owner.getId())));
        try {
            KnowledgeImportService.ImportReport report = importService.importCorpus(principal, root);
            log.info("启动语料导入结果: scanned={}, imported={}, skipped={}, failed={}",
                    report.scanned(), report.imported(), report.skipped(), report.failed());
            if (!report.errors().isEmpty()) {
                report.errors().stream().limit(10).forEach(e -> log.warn("语料导入失败: {}", e));
            }
        } catch (RuntimeException ex) {
            // 导入失败不应阻止应用启动；管理员可修正配置后重启。
            log.error("启动语料导入失败: dir={}", root, ex);
        }
    }
}
