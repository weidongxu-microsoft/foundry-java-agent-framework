package io.github.weidongxu.agentframework.foundry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FoundryCallContextTest {

    @Test
    void currentIsNullByDefault() {
        assertNull(FoundryCallContext.current());
    }

    @Test
    void callWithBindsAndRestores() {
        String result = FoundryCallContext.callWith("call-1", () -> {
            assertEquals("call-1", FoundryCallContext.current());
            return "ok";
        });
        assertEquals("ok", result);
        assertNull(FoundryCallContext.current());
    }

    @Test
    void nestedCallWithRestoresOuterBinding() {
        FoundryCallContext.runWith("outer", () -> {
            assertEquals("outer", FoundryCallContext.current());
            FoundryCallContext.runWith("inner",
                    () -> assertEquals("inner", FoundryCallContext.current()));
            assertEquals("outer", FoundryCallContext.current());
        });
        assertNull(FoundryCallContext.current());
    }

    @Test
    void blankCallIdLeavesBindingUnchanged() {
        FoundryCallContext.runWith(null,
                () -> assertNull(FoundryCallContext.current()));
        FoundryCallContext.runWith("  ",
                () -> assertNull(FoundryCallContext.current()));
        assertNull(FoundryCallContext.current());
    }
}
