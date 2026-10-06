package io.github.yudiiee.aicompanion.OllamaClient;

/**
 * Wrapper class for Ollama API responses that support thinking mode
 * Used by reasoning models like deepseek-r1
 */
public class OllamaThinkingResponse {
    private final String content;
    private final String thinking;
    private final boolean hasThinking;

    public OllamaThinkingResponse(String content, String thinking) {
        this.content = content;
        this.thinking = thinking;
        this.hasThinking = thinking != null && !thinking.isEmpty();
    }

    public String getContent() {
        return content;
    }

    public String getThinking() {
        return thinking;
    }

    public boolean hasThinking() {
        return hasThinking;
    }

    /**
     * Gets the full response combining thinking and content
     * Formats it in <think></think> tags for compatibility with existing code
     */
    public String getFullResponse() {
        if (hasThinking) {
            return "<think>\n" + thinking + "\n</think>\n" + content;
        }
        return content;
    }
}

