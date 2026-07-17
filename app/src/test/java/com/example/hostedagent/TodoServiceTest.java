package com.example.hostedagent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoServiceTest {

    private static TodoItem item(String id, String status) {
        return new TodoItem(id, "content-" + id, status, null);
    }

    @Test
    void readReturnsEmptyForUnknownScope() {
        TodoService svc = new TodoService();
        assertTrue(svc.read("nobody").isEmpty());
    }

    @Test
    void writeThenReadReturnsSameList() {
        TodoService svc = new TodoService();
        List<TodoItem> written = svc.write("u1", List.of(item("a", "pending"), item("b", "completed")));
        assertEquals(2, written.size());
        assertEquals(written, svc.read("u1"));
    }

    @Test
    void writeReplacesEntireList() {
        TodoService svc = new TodoService();
        svc.write("u1", List.of(item("a", "pending"), item("b", "pending")));
        svc.write("u1", List.of(item("c", "in_progress")));
        List<TodoItem> now = svc.read("u1");
        assertEquals(1, now.size());
        assertEquals("c", now.get(0).id());
    }

    @Test
    void scopesAreIsolated() {
        TodoService svc = new TodoService();
        svc.write("u1", List.of(item("a", "pending")));
        svc.write("u2", List.of(item("x", "pending"), item("y", "pending")));
        assertEquals(1, svc.read("u1").size());
        assertEquals(2, svc.read("u2").size());
    }

    @Test
    void blankScopeFallsBackToDefault() {
        TodoService svc = new TodoService();
        svc.write("  ", List.of(item("a", "pending")));
        assertEquals(1, svc.read(null).size());
    }

    @Test
    void clearRemovesList() {
        TodoService svc = new TodoService();
        svc.write("u1", List.of(item("a", "pending")));
        svc.clear("u1");
        assertTrue(svc.read("u1").isEmpty());
    }
}
