package ps.reso.instaeclipse.mods.misc;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.view.View;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class HideTopUiElementsHook {
    private static final WeakHashMap<Activity, Boolean> installedActivities = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> hiddenViews = new WeakHashMap<>();
    private static boolean chatSearchHookInstalled;

    private static int mainFeedActionBarId;
    private static int feedTitleId;
    private static int exploreActionBarId;
    private static int exploreOverflowId;
    private static int inboxListId;
    private static int inboxNotesId;

    private HideTopUiElementsHook() {}

    public static void install(Activity activity) {
        cacheIds(activity);
        installChatSearchHook(activity.getClassLoader());
        apply(activity);
        if (installedActivities.put(activity, true) != null) return;

        activity.getWindow().getDecorView().getViewTreeObserver()
                .addOnGlobalLayoutListener(() -> apply(activity));
    }

    private static void apply(Activity activity) {
        View root = activity.getWindow().getDecorView();
        setHidden(findChild(root, mainFeedActionBarId, feedTitleId), FeatureFlags.hideForYouTitle);
        setHidden(findChild(root, exploreActionBarId, exploreOverflowId), FeatureFlags.hideSearchOverflow);
        setHidden(findChild(root, inboxListId, inboxNotesId), FeatureFlags.hideInboxNotes);
    }

    private static void installChatSearchHook(ClassLoader classLoader) {
        if (chatSearchHookInstalled) return;
        try {
            XposedHelpers.findAndHookMethod("X.NXh", classLoader, "A02",
                    String.class, String.class, List.class, List.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.hideChatSearchAi) return;
                    Object payload = XposedHelpers.getObjectField(param.thisObject, "A0N");
                    param.setObjectExtra("instaeclipse_ai_payload", payload);
                    param.setObjectExtra("instaeclipse_ai_agents", removeAiAgents(payload));
                    param.setObjectExtra("instaeclipse_ai_eligible",
                            XposedHelpers.getBooleanField(param.thisObject, "A0Y"));
                    XposedHelpers.setBooleanField(param.thisObject, "A0Y", false);
                }

                @Override
                @SuppressWarnings("unchecked")
                protected void afterHookedMethod(MethodHookParam param) {
                    Object eligible = param.getObjectExtra("instaeclipse_ai_eligible");
                    if (!(eligible instanceof Boolean)) return;
                    XposedHelpers.setBooleanField(param.thisObject, "A0Y", (Boolean) eligible);
                    Object payload = param.getObjectExtra("instaeclipse_ai_payload");
                    Object agents = param.getObjectExtra("instaeclipse_ai_agents");
                    if (payload instanceof Map && agents != null) {
                        ((Map<Object, Object>) payload).put("agents", agents);
                    }
                }
            });
            chatSearchHookInstalled = true;
            ModuleLog.line("(InstaEclipse | Hide AI search): DirectInbox data hook installed");
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | Hide AI search): DirectInbox data hook failed", t);
        }
    }

    static Object removeAiAgents(Object payload) {
        return payload instanceof Map ? ((Map<?, ?>) payload).remove("agents") : null;
    }

    private static View findChild(View root, int parentId, int childId) {
        if (parentId == 0 || childId == 0) return null;
        View parent = root.findViewById(parentId);
        return parent == null ? null : parent.findViewById(childId);
    }

    private static void setHidden(View view, boolean hidden) {
        if (view == null) return;
        if (hidden) {
            if (!hiddenViews.containsKey(view)) hiddenViews.put(view, view.getVisibility());
            view.setVisibility(View.GONE);
            return;
        }
        Integer originalVisibility = hiddenViews.remove(view);
        if (originalVisibility != null) view.setVisibility(originalVisibility);
    }

    @SuppressLint("DiscouragedApi")
    private static void cacheIds(Activity activity) {
        if (mainFeedActionBarId != 0) return;
        String packageName = activity.getPackageName();
        android.content.res.Resources resources = activity.getResources();
        mainFeedActionBarId = resources.getIdentifier("main_feed_action_bar", "id", packageName);
        feedTitleId = resources.getIdentifier("text_title_chevron_container", "id", packageName);
        exploreActionBarId = resources.getIdentifier("explore_action_bar", "id", packageName);
        exploreOverflowId = resources.getIdentifier("explore_action_bar_right_button_stub", "id", packageName);
        inboxListId = resources.getIdentifier("inbox_refreshable_thread_list_recyclerview", "id", packageName);
        inboxNotesId = resources.getIdentifier("cf_hub_recycler_view", "id", packageName);
    }
}
