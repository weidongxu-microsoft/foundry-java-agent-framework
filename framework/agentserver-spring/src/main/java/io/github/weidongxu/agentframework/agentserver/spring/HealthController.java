package io.github.weidongxu.agentframework.agentserver.spring;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness/readiness probes for a hosted agent container — part of the Core/host layer (the Java
 * equivalent of {@code Azure.AI.AgentServer.Core}'s {@code HealthEndpointExtensions}).
 *
 * <p>The Foundry platform polls {@code GET /readiness} before routing traffic to the container and
 * uses {@code /} and {@code /healthz} for liveness. The {@code /responses} endpoint only serves
 * {@code POST}, so the host provides these probes — a hosted workload no longer needs to hand-roll
 * its own health endpoints.</p>
 */
@RestController
public class HealthController {

    @GetMapping({"/", "/healthz", "/readiness"})
    public String health() {
        return "ok";
    }
}
