package io.github.yudiiee.aicompanion.ChatUtils;

public class ClarificationState {
    public final String botName;
    public final String originalMessage;
    public final String clarifyingQuestion;

    public ClarificationState(String originalMessage, String clarifyingQuestion, String botName) {
        this.originalMessage = originalMessage;
        this.clarifyingQuestion = clarifyingQuestion;
        this.botName = botName;
    }
}
