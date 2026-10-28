package com.campus.platform.ai.tool;

import java.util.List;

/**
 * 按业务模块提供工具集合。每个模块一个实现，注册表在启动时收集并校验工具名唯一。
 */
public interface AiToolProvider {

    List<AiTool> tools();
}
