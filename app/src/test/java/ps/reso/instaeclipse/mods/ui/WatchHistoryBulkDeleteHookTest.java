package ps.reso.instaeclipse.mods.ui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WatchHistoryBulkDeleteHookTest {
    @Test
    public void keepsBatchDelayInRequestedRange() {
        for (int i = 0; i < 100; i++) {
            long delay = WatchHistoryBulkDeleteHook.nextBatchDelayMillis();
            assertTrue(delay >= 1000L && delay <= 3000L);
        }
    }
}
