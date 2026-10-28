package com.campus.platform.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地 Markdown 语料导入配置（前缀 app.knowledge.import）。
 * <p>
 * 默认关闭。仅在开发/演示环境显式配置目录时，启动后把目录中的 .md 文件导入指定知识库。
 * 导入按 source_path + content_hash 幂等：相同内容跳过，内容变化则生成新版本。
 *
 * @param enabled       是否启用
 * @param dir           语料根目录（绝对路径或相对工作目录）
 * @param categoryName  目标分类名，不存在时创建
 * @param spaceName     目标空间名，不存在时创建
 * @param ownerUsername 导入使用的管理员用户名
 */
@ConfigurationProperties(prefix = "app.knowledge.import")
public record KnowledgeImportProperties(
        boolean enabled,
        String dir,
        String categoryName,
        String spaceName,
        String ownerUsername) {

    public KnowledgeImportProperties {
        categoryName = categoryName == null || categoryName.isBlank() ? "SJTU Wiki" : categoryName;
        spaceName = spaceName == null || spaceName.isBlank() ? "SJTU Wiki" : spaceName;
        ownerUsername = ownerUsername == null || ownerUsername.isBlank() ? "admin" : ownerUsername;
    }

    public boolean configured() {
        return enabled && dir != null && !dir.isBlank();
    }
}
