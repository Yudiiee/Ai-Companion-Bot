package io.github.yudiiee.aicompanion.FunctionCaller;

import java.util.List;
import java.util.Set;

public class Tool {
    private final String name;
    private final String description;
    private final List<Parameter> parameters;
    private final Set<String> stateKeys;
    private final ToolStateUpdater stateUpdater;

    public Tool(String name, String description, List<Parameter> parameters, Set<String> stateKeys, ToolStateUpdater stateUpdater) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.stateKeys = stateKeys;
        this.stateUpdater = stateUpdater;
    }

    public String name() { return name; }
    public String description() { return description; }
    public List<Parameter> parameters() { return parameters; }
    public Set<String> stateKeys() { return stateKeys; }
    public ToolStateUpdater stateUpdater() { return stateUpdater; }

    public record Parameter(String name, String description) {}
}

