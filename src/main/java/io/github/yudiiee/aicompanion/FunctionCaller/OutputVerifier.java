package io.github.yudiiee.aicompanion.FunctionCaller;

import java.util.Map;

public interface OutputVerifier {
    /**
     * Verifies whether the function output matches expected conditions.
     * @param parameters - parameters passed to the function
     * @param functionOutput - raw output from the function
     * @return true if valid, false if output is unexpected or invalid
     */
    boolean verify(Map<String, String> parameters, String functionOutput);
}
