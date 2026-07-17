package io.github.weidongxu.agentframework.samples.hosting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sample 04 — host your agent behind the OpenAI Responses protocol.
 *
 * <p>Starts a Spring Boot server that exposes {@code POST /responses} (and {@code GET /health}),
 * backed by a framework agent. This is the minimal, provider-agnostic version of the {@code app/}
 * workload — see {@link HostingConfiguration} for the wiring.
 *
 * <pre>{@code
 *   $env:OPENAI_API_KEY = "sk-..."
 *   mvn -q -f samples\04-hosting\pom.xml spring-boot:run
 *
 *   # in another shell:
 *   curl -X POST http://localhost:8080/responses ^
 *     -H "Content-Type: application/json" ^
 *     -d "{\"input\":\"Say hello in one sentence.\"}"
 * }</pre>
 */
@SpringBootApplication
public class HostingApplication {
    public static void main(String[] args) {
        SpringApplication.run(HostingApplication.class, args);
    }
}
