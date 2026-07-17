package io.github.weidongxu.agentframework.agentserver.spring;

import io.github.weidongxu.agentframework.agentserver.responses.ResponseLifecycleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Spring MVC binding for the Responses <em>lifecycle</em> routes — the Java stand-in for the
 * AgentServer SDK persistence layer that auto-registers them alongside {@code POST /responses}:
 *
 * <ul>
 *   <li>{@code GET /responses/{id}}</li>
 *   <li>{@code POST /responses/{id}/cancel}</li>
 *   <li>{@code DELETE /responses/{id}}</li>
 *   <li>{@code GET /responses/{id}/input_items}</li>
 * </ul>
 *
 * <p>Pure transport: it delegates to a {@link ResponseLifecycleService} (backed by a
 * {@code ResponseStore}) and serializes the {@link ResponseLifecycleService.Result}. Register this
 * bean only when response persistence is enabled.
 */
@RestController
public final class ResponseLifecycleEndpoint {

    private final ResponseLifecycleService service;

    public ResponseLifecycleEndpoint(ResponseLifecycleService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @GetMapping("${agent-framework.responses.path:/responses}/{id}")
    public ResponseEntity<Object> getResponse(@PathVariable("id") String id) {
        return toResponseEntity(service.get(id));
    }

    @PostMapping("${agent-framework.responses.path:/responses}/{id}/cancel")
    public ResponseEntity<Object> cancelResponse(@PathVariable("id") String id) {
        return toResponseEntity(service.cancel(id));
    }

    @DeleteMapping("${agent-framework.responses.path:/responses}/{id}")
    public ResponseEntity<Object> deleteResponse(@PathVariable("id") String id) {
        return toResponseEntity(service.delete(id));
    }

    @GetMapping("${agent-framework.responses.path:/responses}/{id}/input_items")
    public ResponseEntity<Object> listInputItems(
            @PathVariable("id") String id,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "after", required = false) String after,
            @RequestParam(value = "order", required = false) String order) {
        return toResponseEntity(service.listInputItems(id, limit, after, order));
    }

    private static ResponseEntity<Object> toResponseEntity(ResponseLifecycleService.Result result) {
        return ResponseEntity.status(result.getStatus()).body(result.getBody());
    }
}
