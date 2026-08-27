package ps.reso.instaeclipse.mods.misc;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LowMemoryModeHookTest {
    @Test
    public void deleteContentsKeepsRootAndDeletesNestedCache() throws Exception {
        File root = Files.createTempDirectory("instaeclipse-cache").toFile();
        File nested = new File(root, "media/thumbs");
        assertTrue(nested.mkdirs());
        assertTrue(new File(nested, "cached.mp4").createNewFile());

        LowMemoryModeHook.deleteContents(root);

        assertTrue(root.exists());
        assertFalse(new File(root, "media").exists());
        assertTrue(LowMemoryModeHook.isLikelyProfileImage(320, 320));
        assertFalse(LowMemoryModeHook.isLikelyProfileImage(360, 640));
        assertTrue(root.delete());
    }
}
