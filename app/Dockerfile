# Multi-stage build. Pinned JDK 21 for reproducibility regardless of local JDK.
# Build for linux/amd64 — the Foundry hosted-agent runtime rejects arm64 images.
# Run from the repository root:
#   docker build --platform linux/amd64 -f app/Dockerfile -t <image> .

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -B -pl framework/core-impl,framework/openai,framework/foundry,framework/agentserver-spring,framework/agentserver-foundry,framework/mcp,framework/langchain4j \
        -am -DskipTests install \
    && mvn -q -B -f photo/pom.xml -DskipTests install \
    && mvn -q -B -f app/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
# ca-certificates provides the OS trust bundle that the egress proxy CA is appended to.
# git + uv power the OPTIONAL local-MCP demo (MCP_ENABLED=true): the framework spawns the
# read-only `mcp-server-git` over stdio via `uvx`. Both are inert unless MCP is enabled.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates git curl \
    && rm -rf /var/lib/apt/lists/*
# uv provides `uvx`; pre-install the git MCP server so it runs without runtime network access.
RUN curl -LsSf https://astral.sh/uv/install.sh | sh
# NOTE: the raw-photo develop feature (plan/19) shells out to `rawtherapee-cli`. When that feature
# is wired into the agent, add it to the runtime image, e.g.:
#   apt-get install -y --no-install-recommends rawtherapee    # provides rawtherapee-cli
# It is intentionally omitted until then to keep the image lean (the lib is on the classpath but
# unused, so nothing invokes the binary yet).
ENV PATH="/root/.local/bin:${PATH}"
RUN uv tool install mcp-server-git
# Synthetic repo the git MCP demo reads. Its commits carry a unique marker (ZTOKEN-9f3a1c) that
# provably does not exist on the public internet, so answering questions about it forces the model
# onto the git tool rather than hosted web_search. Author/committer identity AND dates are pinned so
# the HEAD commit SHA is byte-for-byte reproducible across builds (the draft-changelog skill e2e
# asserts a 40-hex SHA in the reply, and reproducibility keeps that deterministic).
ENV GIT_AUTHOR_DATE="2024-01-01T00:00:00+0000" \
    GIT_COMMITTER_DATE="2024-01-01T00:00:00+0000"
RUN git config --global user.email "demo@example.com" \
    && git config --global user.name "Demo" \
    && git config --global --add safe.directory /opt/demo-repo \
    && git init -b main /opt/demo-repo \
    && cd /opt/demo-repo \
    && printf 'acme-widget\n' > README.md \
    && git add README.md \
    && git commit -m "seed: acme-widget bootstrap ZTOKEN-9f3a1c" \
    && printf 'ZTOKEN-9f3a1c gizmo module\n' > GIZMO.txt \
    && git add GIZMO.txt \
    && git commit -m "feat: add gizmo module (ZTOKEN-9f3a1c)"
# Bake the Agent Skills (progressive disclosure) the framework serves when SKILLS_ENABLED=true.
COPY app/skills /opt/skills
# This image bakes every dependency both features need (git + uv + mcp-server-git + /opt/demo-repo
# for MCP; /opt/skills for Skills), so enable both by default — the deps and the flags ship as one
# self-consistent artifact. A deployment can still override either flag to "false" via its env map.
ENV MCP_ENABLED=true \
    SKILLS_ENABLED=true
COPY --from=build /workspace/app/target/app.jar /app/app.jar
# Application Insights Java agent: auto-ships Logback logs + request telemetry to App Insights
# using the platform-injected APPLICATIONINSIGHTS_CONNECTION_STRING (attached via -javaagent in
# entrypoint.sh, and only when that connection string is present).
ARG APPLICATIONINSIGHTS_AGENT_VERSION=3.7.9
ADD https://repo1.maven.org/maven2/com/microsoft/azure/applicationinsights-agent/${APPLICATIONINSIGHTS_AGENT_VERSION}/applicationinsights-agent-${APPLICATIONINSIGHTS_AGENT_VERSION}.jar /app/applicationinsights-agent.jar
COPY app/entrypoint.sh /app/entrypoint.sh
RUN sed -i 's/\r$//' /app/entrypoint.sh && chmod +x /app/entrypoint.sh
# Foundry forwards POST /responses to this port.
EXPOSE 8088
ENTRYPOINT ["/app/entrypoint.sh"]
