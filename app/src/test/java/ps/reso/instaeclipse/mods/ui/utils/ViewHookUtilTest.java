package ps.reso.instaeclipse.mods.ui.utils;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ViewHookUtilTest {
    @Test
    public void identifiesReelsTabsWithoutMatchingUnrelatedViews() {
        assertTrue(ViewHookUtil.isReelsTabResourceName("clips_tab"));
        assertTrue(ViewHookUtil.isReelsTabResourceName("reels_tab_icon"));
        assertFalse(ViewHookUtil.isReelsTabResourceName("clips_viewer"));
        assertTrue(ViewHookUtil.isBottomNavigationContainerName("bottom_navigation_bar"));
    }

    @Test
    public void identifiesRefreshFeedErrorsOnly() {
        assertTrue(ViewHookUtil.isRefreshFeedError("Impossibile aggiornare il feed"));
        assertTrue(ViewHookUtil.isRefreshFeedError("Couldn't refresh feed"));
        assertFalse(ViewHookUtil.isRefreshFeedError("Pull to refresh feed"));
    }
}
