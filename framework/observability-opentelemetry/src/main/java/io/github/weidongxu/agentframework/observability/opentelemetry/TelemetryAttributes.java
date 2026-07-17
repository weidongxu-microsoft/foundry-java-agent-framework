package io.github.weidongxu.agentframework.observability.opentelemetry;

final class TelemetryAttributes {
    static final String OPERATION_NAME = "gen_ai.operation.name";
    static final String AGENT_ID = "gen_ai.agent.id";
    static final String AGENT_NAME = "gen_ai.agent.name";
    static final String AGENT_DESCRIPTION = "gen_ai.agent.description";
    static final String REQUEST_MODEL = "gen_ai.request.model";
    static final String RESPONSE_ID = "gen_ai.response.id";
    static final String INPUT_TOKENS = "gen_ai.usage.input_tokens";
    static final String OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
    static final String TOOL_NAME = "gen_ai.tool.name";
    static final String TOOL_CALL_ID = "gen_ai.tool.call.id";
    static final String INPUT_MESSAGES = "gen_ai.input.messages";
    static final String OUTPUT_MESSAGES = "gen_ai.output.messages";
    static final String TOOL_ARGUMENTS = "gen_ai.tool.call.arguments";
    static final String TOOL_RESULT = "gen_ai.tool.call.result";
    static final String ERROR_TYPE = "error.type";
    static final String CANCELLED = "agentframework.cancelled";
    static final String PARENT_CONTEXT =
            "io.github.weidongxu.agentframework.opentelemetry.parentContext";

    private TelemetryAttributes() {
    }
}
