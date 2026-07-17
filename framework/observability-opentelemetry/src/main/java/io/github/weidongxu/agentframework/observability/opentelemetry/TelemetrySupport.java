package io.github.weidongxu.agentframework.observability.opentelemetry;

import io.github.weidongxu.agentframework.chat.ChatMessage;
import io.github.weidongxu.agentframework.chat.FunctionCallContent;
import io.github.weidongxu.agentframework.chat.FunctionResultContent;
import io.github.weidongxu.agentframework.chat.TextContent;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

final class TelemetrySupport {
    private TelemetrySupport() {
    }

    static void fail(Span span, Throwable error) {
        Throwable failure = unwrap(error);
        span.recordException(failure);
        span.setAttribute(
                TelemetryAttributes.ERROR_TYPE,
                failure.getClass().getName());
        span.setStatus(StatusCode.ERROR, failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage());
    }

    static Throwable unwrap(Throwable error) {
        Throwable result = error;
        while ((result instanceof CompletionException
                || result instanceof ExecutionException)
                && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    static String messages(List<ChatMessage> messages) {
        StringBuilder result = new StringBuilder("[");
        for (ChatMessage message : messages) {
            if (result.length() > 1) {
                result.append(',');
            }
            result.append("{\"role\":\"")
                    .append(message.getRole().name().toLowerCase())
                    .append("\",\"parts\":[");
            for (int index = 0; index < message.getContents().size(); index++) {
                if (index > 0) {
                    result.append(',');
                }
                Object content = message.getContents().get(index);
                if (content instanceof TextContent) {
                    result.append("{\"type\":\"text\",\"content\":\"")
                            .append(escape(((TextContent) content).getText()))
                            .append("\"}");
                } else if (content instanceof FunctionCallContent) {
                    FunctionCallContent call = (FunctionCallContent) content;
                    result.append("{\"type\":\"tool_call\",\"id\":\"")
                            .append(escape(call.getCallId()))
                            .append("\",\"name\":\"")
                            .append(escape(call.getName()))
                            .append("\",\"arguments\":")
                            .append(call.getArguments())
                            .append('}');
                } else if (content instanceof FunctionResultContent) {
                    FunctionResultContent functionResult =
                            (FunctionResultContent) content;
                    result.append("{\"type\":\"tool_call_response\",\"id\":\"")
                            .append(escape(functionResult.getCallId()))
                            .append("\",\"response\":\"")
                            .append(escape(functionResult.getResult()))
                            .append("\"}");
                } else {
                    result.append("{\"type\":\"")
                            .append(escape(message.getContents().get(index).getType()))
                            .append("\"}");
                }
            }
            result.append("]}");
        }
        return result.append(']').toString();
    }

    static String assistantMessage(String text) {
        return messages(java.util.Collections.singletonList(
                ChatMessage.assistant(text)));
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    result.append("\\\"");
                    break;
                case '\\':
                    result.append("\\\\");
                    break;
                case '\b':
                    result.append("\\b");
                    break;
                case '\f':
                    result.append("\\f");
                    break;
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '\t':
                    result.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
            }
        }
        return result.toString();
    }

    static void runInSpan(Span span, Runnable action) {
        try (Scope ignored = span.makeCurrent()) {
            action.run();
        }
    }
}
