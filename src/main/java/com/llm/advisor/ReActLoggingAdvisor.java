package com.llm.advisor;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class ReActLoggingAdvisor implements CallAdvisor {

    private final AtomicInteger stepCounter = new AtomicInteger(0);

    @NotNull
    @Override
    public ChatClientResponse adviseCall(@NotNull ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        logStep(request, response);
        return response;
    }

    private void logStep(ChatClientRequest request, ChatClientResponse response) {
        int step = stepCounter.incrementAndGet();
        ChatResponse cr = response.chatResponse();
        if (cr == null || cr.getResult() == null) return;

        AssistantMessage output = cr.getResult().getOutput();

        List<ToolCall> toolCalls = output.getToolCalls();
        String text = output.getText();

        StringBuilder sb = new StringBuilder("[ReAct] Step ").append(step).append(" | ");

        if (!toolCalls.isEmpty()) {
            for (ToolCall tc : toolCalls) {
                sb.append("→ ").append(tc.name()).append("(").append(abbrev(tc.arguments(), 40)).append(")");
            }
        } else {
            String prevToolResult = extractToolResult(request);
            if (prevToolResult != null) {
                sb.append("Observation: ").append(abbrev(prevToolResult, 40)).append(" → ");
            }
            sb.append("Final: ").append(abbrev(text, 80));
        }

        log.info(sb.toString());
    }

    private String extractToolResult(ChatClientRequest request) {
        List<Message> messages = request.prompt().getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ToolResponseMessage trm) {
                if (!trm.getResponses().isEmpty()) {
                    // 使用了 Java 现代特性的 getFirst()
                    return trm.getResponses().getFirst().responseData();
                }
            }
        }
        return null;
    }

    @NotNull
    @Override
    public String getName() {
        return "react-logging";
    }

    @Override
    public int getOrder() {
        return -1;
    }

    private static String abbrev(String s, int maxLen) {
        if (s == null) return "";
        String singleLine = s.replace("\n", " ").trim();
        return singleLine.length() > maxLen ? singleLine.substring(0, maxLen) + "..." : singleLine;
    }
}