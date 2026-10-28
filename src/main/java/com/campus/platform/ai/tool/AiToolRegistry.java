package com.campus.platform.ai.tool;

import com.campus.platform.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表：启动时收集全部 {@link AiToolProvider}，按工具名索引。
 * <p>
 * 工具名重复属于编码错误，启动即失败，避免运行期出现“同名叫不同实现”的歧义。
 */
@Service
public class AiToolRegistry {

    private final Map<String, AiTool> tools;

    public AiToolRegistry(List<AiToolProvider> providers) {
        Map<String, AiTool> map = new LinkedHashMap<>();
        for (AiToolProvider provider : providers) {
            for (AiTool tool : provider.tools()) {
                AiTool previous = map.putIfAbsent(tool.name(), tool);
                if (previous != null) {
                    throw new IllegalStateException("AI 工具名重复: " + tool.name());
                }
            }
        }
        this.tools = java.util.Collections.unmodifiableMap(map);
    }

    public Optional<AiTool> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(tools.get(name));
    }

    public AiTool require(String name) {
        return find(name).orElseThrow(() -> BusinessException.notFound("工具"));
    }

    /** 全部工具，顺序由 Provider 声明顺序决定（便于文档与前端稳定展示）。 */
    public List<AiTool> all() {
        return List.copyOf(tools.values());
    }
}
