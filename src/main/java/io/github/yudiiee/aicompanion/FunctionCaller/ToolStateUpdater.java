package io.github.yudiiee.aicompanion.FunctionCaller;

import java.util.Map;

@FunctionalInterface
public interface ToolStateUpdater {
    void update(Map<String, Object> sharedState, Map<String, String> paramMap, Object functionResult);
}
