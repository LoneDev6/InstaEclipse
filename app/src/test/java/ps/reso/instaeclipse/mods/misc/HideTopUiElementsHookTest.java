package ps.reso.instaeclipse.mods.misc;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class HideTopUiElementsHookTest {
    @Test
    public void removesOnlyAiAgentsFromSearchPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("agents", "ai-results");
        payload.put("suggested", "chat-results");

        assertEquals("ai-results", HideTopUiElementsHook.removeAiAgents(payload));
        assertFalse(payload.containsKey("agents"));
        assertEquals("chat-results", payload.get("suggested"));
    }
}
