package com.example.hostedagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Foundry hosted agent container.
 *
 * <p>The container exposes the OpenAI Responses protocol on port 8088. Foundry runs this image
 * in a per-session sandbox and forwards client {@code POST /responses} calls to it. The container
 * is a <em>coded</em> agent built on the Java Agent Framework: it runs the model + tool loop
 * in-process (web search + code interpreter + a local todo tool) and drives the Foundry memory
 * store directly — there is no backing prompt agent.</p>
 */
@SpringBootApplication
public class HostedAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(HostedAgentApplication.class, args);
    }
}
