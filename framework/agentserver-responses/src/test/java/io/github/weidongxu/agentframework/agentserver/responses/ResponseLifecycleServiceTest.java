package io.github.weidongxu.agentframework.agentserver.responses;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseLifecycleServiceTest {

    private final ResponseStore store = new InMemoryResponseStore();
    private final ResponseLifecycleService service = new ResponseLifecycleService(store);

    private static Map<String, Object> envelope(String id, String status) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("id", id);
        env.put("object", "response");
        env.put("status", status);
        return env;
    }

    private static Map<String, Object> inputItem(String id) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("type", "message");
        item.put("role", "user");
        return item;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseLifecycleService.Result result) {
        return (Map<String, Object>) result.getBody();
    }

    @Test
    void getReturnsEnvelope() {
        store.save(new StoredResponse("resp_1", 1L, "completed", false,
                envelope("resp_1", "completed"), List.of()));

        ResponseLifecycleService.Result result = service.get("resp_1");

        assertEquals(200, result.getStatus());
        assertEquals("resp_1", body(result).get("id"));
    }

    @Test
    void getUnknownReturns404() {
        ResponseLifecycleService.Result result = service.get("missing");
        assertEquals(404, result.getStatus());
    }

    @Test
    void cancelNonBackgroundReturns400() {
        store.save(new StoredResponse("resp_2", 1L, "completed", false,
                envelope("resp_2", "completed"), List.of()));

        ResponseLifecycleService.Result result = service.cancel("resp_2");

        assertEquals(400, result.getStatus());
    }

    @Test
    void cancelBackgroundSetsCancelled() {
        store.save(new StoredResponse("resp_3", 1L, "in_progress", true,
                envelope("resp_3", "in_progress"), List.of()));

        ResponseLifecycleService.Result result = service.cancel("resp_3");

        assertEquals(200, result.getStatus());
        assertEquals("cancelled", body(result).get("status"));
        assertEquals("cancelled", store.get("resp_3").get().getStatus());
    }

    @Test
    void cancelTerminalBackgroundReturns400() {
        store.save(new StoredResponse("resp_4", 1L, "completed", true,
                envelope("resp_4", "completed"), List.of()));

        ResponseLifecycleService.Result result = service.cancel("resp_4");

        assertEquals(400, result.getStatus());
    }

    @Test
    void deleteRemovesResponse() {
        store.save(new StoredResponse("resp_5", 1L, "completed", false,
                envelope("resp_5", "completed"), List.of()));

        ResponseLifecycleService.Result result = service.delete("resp_5");

        assertEquals(200, result.getStatus());
        assertEquals(Boolean.TRUE, body(result).get("deleted"));
        assertFalse(store.get("resp_5").isPresent());
    }

    @Test
    void deleteUnknownReturns404() {
        assertEquals(404, service.delete("missing").getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listInputItemsPaginates() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            items.add(inputItem("item_" + i));
        }
        store.save(new StoredResponse("resp_6", 1L, "completed", false,
                envelope("resp_6", "completed"), items));

        ResponseLifecycleService.Result first = service.listInputItems("resp_6", 2, null, "asc");
        Map<String, Object> firstBody = body(first);
        List<Map<String, Object>> firstPage = (List<Map<String, Object>>) firstBody.get("data");

        assertEquals(200, first.getStatus());
        assertEquals(2, firstPage.size());
        assertEquals("item_0", firstPage.get(0).get("id"));
        assertEquals(Boolean.TRUE, firstBody.get("has_more"));
        assertEquals("item_1", firstBody.get("last_id"));

        ResponseLifecycleService.Result next =
                service.listInputItems("resp_6", 2, "item_1", "asc");
        List<Map<String, Object>> nextPage = (List<Map<String, Object>>) body(next).get("data");
        assertEquals("item_2", nextPage.get(0).get("id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listInputItemsDescReverses() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(inputItem("a"));
        items.add(inputItem("b"));
        store.save(new StoredResponse("resp_7", 1L, "completed", false,
                envelope("resp_7", "completed"), items));

        ResponseLifecycleService.Result result =
                service.listInputItems("resp_7", null, null, "desc");
        List<Map<String, Object>> page = (List<Map<String, Object>>) body(result).get("data");

        assertEquals("b", page.get(0).get("id"));
        assertFalse((Boolean) body(result).get("has_more"));
    }

    @Test
    void listInputItemsUnknownReturns404() {
        assertEquals(404, service.listInputItems("missing", null, null, "asc").getStatus());
    }
}
