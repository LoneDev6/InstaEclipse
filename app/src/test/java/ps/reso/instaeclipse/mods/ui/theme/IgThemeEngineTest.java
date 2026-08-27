package ps.reso.instaeclipse.mods.ui.theme;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class IgThemeEngineTest {
    @Test
    public void mapsDirectThreadBackgroundsToPaletteSlots() {
        assertEquals(0, IgThemeEngine.slotForAttrName("backgroundColorPrimary"));
        assertEquals(0, IgThemeEngine.slotForAttrName("directThreadActionBarBackgroundColor"));
        assertEquals(0, IgThemeEngine.slotForAttrName("messageComposerBackgroundColor"));
        assertEquals(1, IgThemeEngine.slotForAttrName("backgroundColorSecondary"));
        assertEquals(1, IgThemeEngine.slotForAttrName("messageComposerRedesignBackgroundColor"));
        assertEquals(1, IgThemeEngine.slotForAttrName("messageFromOthersGrayBackground"));
    }
}
