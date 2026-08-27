package ps.reso.instaeclipse.mods.ui.utils;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

public class ViewHookUtil {
    public static View findStoriesRecycler(View root) {
        View tray = findStoriesTray(root);
        return tray == null ? null : findByClassName(tray, "androidx.recyclerview.widget.RecyclerView");
    }

    public static View findStoriesTray(View root) {
        View visible = findShownByResourceName(root, "reels_tray_container");
        return visible != null ? visible : findByResourceName(root, "reels_tray_container");
    }

    public static boolean isRefreshFeedError(CharSequence text) {
        if (text == null) return false;
        String normalized = text.toString().trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("impossibile aggiornare il feed")
                || normalized.contains("impossibile caricare il feed")
                || ((normalized.contains("refresh feed") || normalized.contains("refresh the feed"))
                && (normalized.contains("couldn't") || normalized.contains("could not")
                || normalized.contains("can't") || normalized.contains("cannot")
                || normalized.contains("unable")));
    }

    public static View findReelsTab(View root) {
        if (root.getId() != View.NO_ID) {
            try {
                if (isReelsTabResourceName(root.getResources().getResourceEntryName(root.getId()))) return root;
            } catch (Resources.NotFoundException ignored) {}
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findReelsTab(group.getChildAt(i));
                if (match != null) return match;
            }
        }
        return null;
    }

    public static View findBottomBarItem(View reelsView) {
        View item = reelsView;
        ViewParent parent = reelsView.getParent();
        for (int depth = 0; depth < 6 && parent instanceof View; depth++) {
            View parentView = (View) parent;
            if (parentView.getId() != View.NO_ID) {
                try {
                    String name = parentView.getResources().getResourceEntryName(parentView.getId());
                    if (isBottomNavigationContainerName(name)) return item;
                } catch (Resources.NotFoundException ignored) {}
            }
            item = parentView;
            parent = parentView.getParent();
        }
        return null;
    }

    static boolean isReelsTabResourceName(String name) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        return (normalized.contains("reel") || normalized.contains("clips"))
                && (normalized.contains("tab") || normalized.contains("navigation"));
    }

    static boolean isBottomNavigationContainerName(String name) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("tab_bar") || normalized.contains("bottom_navigation")
                || normalized.contains("tabbed_navigation") || normalized.contains("navigation_bar");
    }

    private static View findByResourceName(View root, String resourceName) {
        if (root.getId() != View.NO_ID) {
            try {
                if (resourceName.equals(root.getResources().getResourceEntryName(root.getId()))) return root;
            } catch (Resources.NotFoundException ignored) {}
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findByResourceName(group.getChildAt(i), resourceName);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static View findShownByResourceName(View root, String resourceName) {
        if (root.getId() != View.NO_ID && root.isShown() && root.isAttachedToWindow()) {
            try {
                if (resourceName.equals(root.getResources().getResourceEntryName(root.getId()))) return root;
            } catch (Resources.NotFoundException ignored) {}
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findShownByResourceName(group.getChildAt(i), resourceName);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static View findByClassName(View root, String className) {
        if (className.equals(root.getClass().getName())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findByClassName(group.getChildAt(i), className);
                if (match != null) return match;
            }
        }
        return null;
    }

    // LOGGER FOR DEV PURPOSES
    /*
    private static void logAllViewIds(View view, Resources res, String packageName, String indent) {
        if (view.getId() != View.NO_ID) {
            try {
                String idName = res.getResourceEntryName(view.getId());
                ModuleLog.line(indent + "View ID: " + idName + " (" + view.getClass().getSimpleName() + ")");
            } catch (Resources.NotFoundException e) {
                // Might be a generated ID or from another package
                ModuleLog.line(indent + "Unknown ID: " + view.getId() + " (" + view.getClass().getSimpleName() + ")");
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                logAllViewIds(group.getChildAt(i), res, packageName, indent + "  ");
            }
        }
    }
    */
}
