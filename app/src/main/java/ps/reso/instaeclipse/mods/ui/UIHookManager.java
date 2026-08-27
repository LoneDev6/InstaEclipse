package ps.reso.instaeclipse.mods.ui;

import static org.luckypray.dexkit.query.FindMethod.create;
import static ps.reso.instaeclipse.mods.ghost.ui.GhostEmojiManager.addGhostEmojiNextToInbox;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.mods.devops.config.ConfigManager;
import ps.reso.instaeclipse.mods.misc.HideTopUiElementsHook;
import ps.reso.instaeclipse.mods.ui.utils.BottomSheetHookUtil;
import ps.reso.instaeclipse.mods.ui.utils.VibrationUtil;
import ps.reso.instaeclipse.mods.ui.utils.ViewHookUtil;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.core.ModuleActivityLauncher;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.GhostModeUtils;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.toast.CustomToast;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class UIHookManager {

    private static final String INSTAGRAM_MAIN_ACTIVITY = "com.instagram.mainactivity.InstagramMainActivity";

    @SuppressLint("StaticFieldLeak")
    private static Activity currentActivity;
    public static Activity getCurrentActivity() {
        return currentActivity;
    }

    /**
     * Activities that already have a pending OnGlobalLayoutListener registered.
     * Used only to prevent duplicate listener registrations when the search view
     * is not yet visible at setup time.
     */
    private static final java.util.WeakHashMap<Activity, Boolean> sGlobalListenerPending =
            new java.util.WeakHashMap<>();
    /**
     * Tracks whether the GlobalLayoutListener path has completed for an activity,
     * so we don't keep registering new listeners on every resume after search is found.
     */
    private static final java.util.WeakHashMap<Activity, Boolean> sSearchWiringDone =
            new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, Integer> sHiddenReelsTabs =
            new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, Integer> sHiddenExploreGrids =
            new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<Activity, Boolean> sExploreGridListenerPending =
            new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<TextView, Integer> sHiddenRefreshMessages =
            new java.util.WeakHashMap<>();
    private static volatile boolean sDistractionMessageHookInstalled;
    private static volatile boolean sStoriesGridHooksInstalled;
    private static volatile boolean sStoriesGridReady;
    private static Method sStoriesLoadMoreMethod;
    private static final java.util.WeakHashMap<Object, Integer> sStoriesPageSizes =
            new java.util.WeakHashMap<>();

    // Resource IDs are constant for a given IG install — cache them statically.
    private static volatile int sSearchTabId = 0;
    private static volatile int sActionBarEndId = 0;
    private static volatile int sInboxButtonId = 0;
    private static volatile int sDirectTabId = 0;
    private static volatile int sExploreActionBarId = 0;
    private static volatile int sExploreRecyclerId = 0;

    @SuppressLint("DiscouragedApi")
    private static void ensureIdsCached(Activity activity) {
        if (sSearchTabId != 0 && sActionBarEndId != 0
                && sInboxButtonId != 0 && sDirectTabId != 0
                && sExploreActionBarId != 0 && sExploreRecyclerId != 0) return;
        String pkg = activity.getPackageName();
        android.content.res.Resources res = activity.getResources();
        if (sSearchTabId == 0)
            sSearchTabId = res.getIdentifier("search_tab", "id", pkg);
        if (sActionBarEndId == 0)
            sActionBarEndId = res.getIdentifier("action_bar_end_action_buttons", "id", pkg);
        if (sInboxButtonId == 0)
            sInboxButtonId = res.getIdentifier("action_bar_inbox_button", "id", pkg);
        if (sDirectTabId == 0)
            sDirectTabId = res.getIdentifier("direct_tab", "id", pkg);
        if (sExploreActionBarId == 0)
            sExploreActionBarId = res.getIdentifier("explore_action_bar", "id", pkg);
        if (sExploreRecyclerId == 0)
            sExploreRecyclerId = res.getIdentifier("recycler_view", "id", pkg);
    }

    public static void setupHooks(Activity activity) {
        // Ghost emoji visibility must update on every resume (reflects current ghost state).
        addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());

        // Cache resource IDs once per IG install (string table lookup is non-trivial).
        ensureIdsCached(activity);
        HideTopUiElementsHook.install(activity);
        applyReelsTabVisibility(activity);
        applyExploreGridVisibility(activity);
        if (sStoriesGridReady) applyStoriesGrid(activity);

        // Always re-apply the search long-press listener. Instagram may overwrite it after
        // a config change (e.g. when InstaEclipse settings are toggled), so we cannot skip
        // this on resume — we just avoid the expensive getIdentifier() call via the cache.
        boolean anySearchFound = false;
        if (sSearchTabId != 0) {
            View v = activity.findViewById(sSearchTabId);
            if (v != null) { processSearchView(activity, v, "search_tab"); anySearchFound = true; }
        }
        if (!anySearchFound && sActionBarEndId != 0) {
            View v = activity.findViewById(sActionBarEndId);
            if (v != null) { processSearchView(activity, v, "action_bar_end_action_buttons"); anySearchFound = true; }
        }

        // Register at most ONE GlobalLayoutListener per activity to retry search wiring
        // when the view isn't inflated yet. Skip if we already found search, if a listener
        // is already pending, or if the listener already completed successfully.
        if (!anySearchFound
                && !Boolean.TRUE.equals(sGlobalListenerPending.get(activity))
                && !Boolean.TRUE.equals(sSearchWiringDone.get(activity))) {
            sGlobalListenerPending.put(activity, true);
            final View decorView = activity.getWindow().getDecorView();
            decorView.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    boolean found = false;
                    if (sSearchTabId != 0) {
                        View lateView = activity.findViewById(sSearchTabId);
                        if (lateView != null) { processSearchView(activity, lateView, "search_tab"); found = true; }
                    }
                    if (!found && sActionBarEndId != 0) {
                        View lateView = activity.findViewById(sActionBarEndId);
                        if (lateView != null) { processSearchView(activity, lateView, "action_bar_end_action_buttons"); found = true; }
                    }
                    if (found) {
                        decorView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        sGlobalListenerPending.remove(activity);
                        sSearchWiringDone.put(activity, true);
                    }
                }
            });
        }

    }

    private static void applyReelsTabVisibility(Activity activity) {
        View reelsView = ViewHookUtil.findReelsTab(activity.getWindow().getDecorView());
        if (reelsView == null) return;

        View tabItem = ViewHookUtil.findBottomBarItem(reelsView);
        if (tabItem == null) return;

        if (FeatureFlags.hideReelsTab) {
            if (!sHiddenReelsTabs.containsKey(tabItem)) {
                sHiddenReelsTabs.put(tabItem, tabItem.getVisibility());
            }
            tabItem.setVisibility(View.GONE);
        } else {
            Integer originalVisibility = sHiddenReelsTabs.remove(tabItem);
            if (originalVisibility != null) tabItem.setVisibility(originalVisibility);
        }
    }

    private static View findExploreGrid(Activity activity) {
        View actionBar = activity.findViewById(sExploreActionBarId);
        if (actionBar == null || !actionBar.isShown() || !(actionBar.getParent() instanceof View)) return null;
        return ((View) actionBar.getParent()).findViewById(sExploreRecyclerId);
    }

    private static boolean updateExploreGridVisibility(Activity activity) {
        View grid = findExploreGrid(activity);
        if (grid == null) return false;

        if (FeatureFlags.disableReels || FeatureFlags.disableReelsExceptDM) {
            if (!sHiddenExploreGrids.containsKey(grid)) {
                sHiddenExploreGrids.put(grid, grid.getVisibility());
            }
            grid.setVisibility(View.GONE);
        } else {
            Integer originalVisibility = sHiddenExploreGrids.remove(grid);
            if (originalVisibility != null) grid.setVisibility(originalVisibility);
        }
        return true;
    }

    private static void applyExploreGridVisibility(Activity activity) {
        if (updateExploreGridVisibility(activity)
                || (!FeatureFlags.disableReels && !FeatureFlags.disableReelsExceptDM)
                || Boolean.TRUE.equals(sExploreGridListenerPending.get(activity))) return;

        sExploreGridListenerPending.put(activity, true);
        View decorView = activity.getWindow().getDecorView();
        decorView.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (!updateExploreGridVisibility(activity)
                                && (FeatureFlags.disableReels || FeatureFlags.disableReelsExceptDM)) return;
                        decorView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        sExploreGridListenerPending.remove(activity);
                    }
                });
    }

    private static void applyStoriesGrid(Activity activity) {
        if (!FeatureFlags.storiesGridLayout) return;

        View root = activity.getWindow().getDecorView();
        View tray = ViewHookUtil.findStoriesTray(root);
        View recycler = ViewHookUtil.findStoriesRecycler(root);
        if (tray == null || recycler == null) return;

        try {
            Class<?> gridClass = XposedHelpers.findClass(
                    "androidx.recyclerview.widget.GridLayoutManager", recycler.getClass().getClassLoader());
            Object currentLayout = XposedHelpers.callMethod(recycler, "getLayoutManager");
            if (!gridClass.isInstance(currentLayout)) {
                Object grid = XposedHelpers.newInstance(gridClass, activity, 4);
                XposedHelpers.callMethod(recycler, "setLayoutManager", grid);
            }
            recycler.setNestedScrollingEnabled(false);

            View current = recycler;
            while (current != null) {
                ViewGroup.LayoutParams params = current.getLayoutParams();
                if (params != null && params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    current.setLayoutParams(params);
                }
                if (current == tray || !(current.getParent() instanceof View)) break;
                current = (View) current.getParent();
            }
            recycler.requestLayout();
        } catch (Throwable e) {
            ModuleLog.line("(InstaEclipse | StoriesGrid): " + e.getMessage());
        }
    }

    private static boolean isAnyDistractionOptionEnabled() {
        return FeatureFlags.isExtremeMode || FeatureFlags.isDistractionFree
                || FeatureFlags.disableStories || FeatureFlags.disableFeed
                || FeatureFlags.disableReels || FeatureFlags.disableReelsExceptDM
                || FeatureFlags.disableExplore || FeatureFlags.disableComments;
    }

    private static void installDistractionMessageHook() {
        if (sDistractionMessageHookInstalled) return;

        XposedHelpers.findAndHookMethod(TextView.class, "setText",
                CharSequence.class, TextView.BufferType.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length == 0 || !(param.args[0] instanceof CharSequence)) return;

                        TextView view = (TextView) param.thisObject;
                        if (isAnyDistractionOptionEnabled()
                                && ViewHookUtil.isRefreshFeedError((CharSequence) param.args[0])) {
                            if (!sHiddenRefreshMessages.containsKey(view)) {
                                sHiddenRefreshMessages.put(view, view.getVisibility());
                            }
                            view.setVisibility(View.GONE);
                            param.args[0] = "";
                            return;
                        }

                        Integer originalVisibility = sHiddenRefreshMessages.remove(view);
                        if (originalVisibility != null) view.setVisibility(originalVisibility);
                    }
                });
        sDistractionMessageHookInstalled = true;
    }

    private static void requestNextStoriesPage(Object controller, View recycler) {
        Method method = sStoriesLoadMoreMethod;

        try {
            if (method == null) {
                for (Field field : controller.getClass().getDeclaredFields()) {
                    try {
                        method = field.getType().getDeclaredMethod("AxA", String.class);
                        method.setAccessible(true);
                        sStoriesLoadMoreMethod = method;
                        break;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }
            if (method == null) return;

            Object adapter = XposedHelpers.callMethod(recycler, "getAdapter");
            if (adapter == null) return;
            int itemCount = (int) XposedHelpers.callMethod(adapter, "getItemCount");

            Object manager = null;
            for (Field field : controller.getClass().getDeclaredFields()) {
                if (field.getType() == method.getDeclaringClass()) {
                    field.setAccessible(true);
                    manager = field.get(controller);
                    break;
                }
            }
            if (manager == null) return;

            Integer previousCount = sStoriesPageSizes.put(manager, itemCount);
            if (previousCount != null && itemCount <= previousCount) {
                sStoriesPageSizes.remove(manager);
                return;
            }
            method.invoke(manager, "feed_timeline");
        } catch (Throwable e) {
            ModuleLog.line("(InstaEclipse | Stories pagination): " + e.getMessage());
        }
    }

    private static void installStoriesGridCallback(String marker, boolean marksReady,
                                                    boolean loadsNextPage) {
        try {
            List<MethodData> methods = Module.dexKitBridge.findMethod(create()
                    .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                            .usingStrings(marker)
                            .returnType("void")));
            if (methods.isEmpty()) return;

            XposedBridge.hookMethod(methods.get(0).getMethodInstance(Module.hostClassLoader),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (marksReady) sStoriesGridReady = true;
                            Activity activity = currentActivity;
                            if (!sStoriesGridReady || activity == null) return;
                            activity.runOnUiThread(() -> {
                                applyStoriesGrid(activity);
                                if (!loadsNextPage || !FeatureFlags.storiesGridLayout) return;
                                View recycler = ViewHookUtil.findStoriesRecycler(
                                        activity.getWindow().getDecorView());
                                if (recycler != null) recycler.post(() ->
                                        requestNextStoriesPage(param.thisObject, recycler));
                            });
                        }
                    });
        } catch (Throwable e) {
            ModuleLog.line("(InstaEclipse | StoriesGrid hook): " + e.getMessage());
        }
    }

    private static void installStoriesGridHooks() {
        if (sStoriesGridHooksInstalled) return;
        try {
            List<MethodData> methods = Module.dexKitBridge.findMethod(create()
                    .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                            .usingStrings("ReelApiUtil.createReelsTrayPageRequestTask")
                            .paramTypes("java.lang.String")
                            .returnType("void")));
            if (!methods.isEmpty()) {
                sStoriesLoadMoreMethod = methods.get(0).getMethodInstance(Module.hostClassLoader);
                sStoriesLoadMoreMethod.setAccessible(true);
            }

            Class<?> recyclerClass = XposedHelpers.findClass(
                    "androidx.recyclerview.widget.RecyclerView", Module.hostClassLoader);
            Method setLayoutManager = null;
            for (Method method : recyclerClass.getDeclaredMethods()) {
                if (method.getName().equals("setLayoutManager") && method.getParameterCount() == 1) {
                    setLayoutManager = method;
                    break;
                }
            }
            if (setLayoutManager != null) {
                XposedBridge.hookMethod(setLayoutManager, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = currentActivity;
                            if (!sStoriesGridReady || !FeatureFlags.storiesGridLayout
                                    || activity == null) return;
                            View recycler = ViewHookUtil.findStoriesRecycler(
                                    activity.getWindow().getDecorView());
                            if (recycler == param.thisObject) applyStoriesGrid(activity);
                        }
                    });
            }

            Method onAttachedToWindow = recyclerClass.getDeclaredMethod("onAttachedToWindow");
            XposedBridge.hookMethod(onAttachedToWindow, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = currentActivity;
                    if (!sStoriesGridReady || !FeatureFlags.storiesGridLayout
                            || activity == null) return;
                    View attached = (View) param.thisObject;
                    attached.post(() -> {
                        View recycler = ViewHookUtil.findStoriesRecycler(
                                activity.getWindow().getDecorView());
                        if (recycler == attached) applyStoriesGrid(activity);
                    });
                }
            });
        } catch (Throwable e) {
            ModuleLog.line("(InstaEclipse | Stories setup): " + e.getMessage());
        }

        installStoriesGridCallback("REEL_TRAY_REQUEST_FINISHED", true, true);
        installStoriesGridCallback("MainFeedReelTrayController.onResume", false, false);
        sStoriesGridHooksInstalled = true;
    }

    public void mainActivity(ClassLoader classLoader) {
        installDistractionMessageHook();
        installStoriesGridHooks();

        // Hook onCreate of Instagram Main
        try {
            // Precise search for the standard onCreate(Bundle) signature
            var methods = Module.dexKitBridge.findMethod(create()
                    .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                            .declaredClass(INSTAGRAM_MAIN_ACTIVITY)
                            .name("onCreate")
                            .paramTypes("android.os.Bundle")
                            .returnType("void")
                    )
            );

            // Fallback: If "onCreate" is renamed/obfuscated but still takes a Bundle
            if (methods.isEmpty()) {
                ModuleLog.line("(InstaEclipse): ⚠️ Specific onCreate not found, searching by signature...");
                methods = Module.dexKitBridge.findMethod(create()
                        .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                                .declaredClass(INSTAGRAM_MAIN_ACTIVITY)
                                .paramTypes("android.os.Bundle")
                                .returnType("void")
                        )
                );
            }

            if (!methods.isEmpty()) {
                String methodName = methods.get(0).getName();
                if (methodName == null || methodName.isEmpty()) {
                    ModuleLog.line("(InstaEclipse): ❌ Invalid onCreate method name discovered");
                } else {
                    XposedHelpers.findAndHookMethod(INSTAGRAM_MAIN_ACTIVITY, classLoader, methodName, Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final Activity activity = (Activity) param.thisObject;
                        currentActivity = activity;

                        // Use runOnUiThread to ensure we are touching the UI safely
                        activity.runOnUiThread(() -> {
                            try {
                                // 1. Initialize Hooks
                                setupHooks(activity);

                                // 2. Delay UI injections slightly.
                                // Instagram's Main is complex; the Inbox/UI might not be inflated immediately.
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    try {
                                        // Add the Ghost Emoji next to Inbox
                                        addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());
                                        applyReelsTabVisibility(activity);

                                        // 3. Show Success Toast
                                        if (FeatureFlags.showFeatureToasts && !CustomToast.toastShown) {
                                            CustomToast.toastShown = true;

                                            StringBuilder sb = new StringBuilder(I18n.t(activity, R.string.ig_toast_features_loaded)).append("\n");
                                            for (Map.Entry<String, Boolean> entry : FeatureStatusTracker.getStatus().entrySet()) {
                                                sb.append(entry.getValue() ? "✅ " : "❌ ").append(FeatureStatusTracker.getLabel(activity, entry.getKey())).append("\n");
                                            }
                                            CustomToast.showCustomToast(activity.getApplicationContext(), sb.toString().trim());
                                        }
                                    } catch (Exception innerE) {
                                        ModuleLog.line("(InstaEclipse): UI Injection Error: " + innerE.getMessage());
                                    }
                                }, 1500); // 1.5s delay to let the UI settle

                            } catch (Exception e) {
                                ModuleLog.line("(InstaEclipse): UI logic error in onCreate: " + e);
                            }
                        });
                    }
                    });
                } // end else (valid methodName)
            } else {
                ModuleLog.line("(InstaEclipse): ❌ Failed to find any onCreate candidate in InstagramMainActivity");
            }
        } catch (Exception e) {
            ModuleLog.line("(InstaEclipse): ❌ DexKit discovery failed: " + e.getMessage());
        }

        // Hook onResume - Instagram Main
        try {
            List<MethodData> candidates = Module.dexKitBridge.findMethod(org.luckypray.dexkit.query.FindMethod.create()
                    .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                            .declaredClass(INSTAGRAM_MAIN_ACTIVITY)
                            .modifiers(java.lang.reflect.Modifier.PUBLIC)
                            .paramCount(0)
                            .returnType("void")
                    )
            );

            for (MethodData methodData : candidates) {
                String methodName = methodData.getName();

                if (methodName == null || methodName.isEmpty()) continue;

                // Skip constructors and static initializers
                if (methodName.contains("<init>") || methodName.contains("<clinit>")) {
                    continue;
                }

                // Filter by opcode size to find the substantial lifecycle method
                if (methodData.getOpCodes().size() < 20) {
                    continue;
                }

                XposedHelpers.findAndHookMethod(INSTAGRAM_MAIN_ACTIVITY, classLoader, methodName, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        final Activity activity = (Activity) param.thisObject;
                        currentActivity = activity;
                        activity.runOnUiThread(() -> {
                            try {
                                setupHooks(activity);
                            } catch (Exception e) {
                                ModuleLog.line("(InstaEclipse) UI Error: " + e);
                            }
                        });
                    }
                });
                break;
            }
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse): ❌ onResume discovery failed: " + t.getMessage());
        }

        // Hook getBottomSheetNavigator - Instagram Main
        BottomSheetHookUtil.hookBottomSheetNavigator(Module.dexKitBridge);

        // Hook View.performLongClick — inbox long-press override.
        // setOnLongClickListener is unreliable when Instagram has a parent-level touch
        // interceptor or a custom view that overrides long-press dispatch. Hooking
        // performLongClick() fires BEFORE any listener/interceptor chain and lets us
        // fully own the event by returning true via setResult.
        // This only fires when the user actually long-presses something — not a hot path.
        XposedHelpers.findAndHookMethod(View.class, "performLongClick", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                int id = view.getId();
                Activity activity = currentActivity;
                if (id == sSearchTabId && activity != null) {
                    openSettings(activity);
                    param.setResult(true);
                    return;
                }
                if (sInboxButtonId == 0 && sDirectTabId == 0) return;
                if (id != sInboxButtonId && id != sDirectTabId) return;
                if (activity == null) return;
                GhostModeUtils.toggleSelectedGhostOptions(activity);
                VibrationUtil.vibrate(activity);
                param.setResult(true); // consume — skip Instagram's handler entirely
            }
        });

        // Hook onResume - Model
        XposedHelpers.findAndHookMethod("com.instagram.modal.ModalActivity", classLoader, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (activity != null) {
                    activity.runOnUiThread(() -> {
                        try {
                            setupHooks(activity);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
        });
    }

    private static void applySearchHook(Activity activity, View v) {
        v.setOnLongClickListener(view -> { openSettings(activity); return true; });
    }

    private static void openSettings(Activity activity) {
        Bundle extras = new Bundle();
        extras.putBoolean("settings_only", true);
        ModuleActivityLauncher.launch(activity, "ps.reso.instaeclipse.MainActivity", extras);
        VibrationUtil.vibrate(activity);
    }

    private static void processSearchView(Activity activity, View view, String id) {
        if (id.equals("action_bar_end_action_buttons") && view instanceof ViewGroup container) {
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                CharSequence description = child.getContentDescription();
                if (description != null && description.toString().toLowerCase().contains("search")) {
                    applySearchHook(activity, child);
                }
            }
        } else {
            applySearchHook(activity, view);
        }
    }

    /** Registers a broadcast receiver in the Instagram process to handle config imports. */
    public static void registerConfigImportReceiver(android.content.Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ctx, Intent intent) {
                String json = intent.getStringExtra("json_content");
                if (json != null && !json.isEmpty()) {
                    ConfigManager.importConfigFromJson(ctx, json);
                }
            }
        };
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver,
                    new IntentFilter("ps.reso.instaeclipse.ACTION_IMPORT_CONFIG"),
                    android.content.Context.RECEIVER_EXPORTED);
        } else {
            androidx.core.content.ContextCompat.registerReceiver(context,
                    receiver,
                    new IntentFilter("ps.reso.instaeclipse.ACTION_IMPORT_CONFIG"),
                    androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
        }
    }

    /** Registers a receiver in the Instagram process to restore settings from a backup JSON. */
    public static void registerSettingsRestoreReceiver(android.content.Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ctx, Intent intent) {
                String json = intent.getStringExtra("json_content");
                if (json == null || json.isEmpty()) return;
                new Thread(() -> {
                    try {
                        ps.reso.instaeclipse.utils.backup.SettingsBackupManager.fromJson(json);
                        SettingsManager.saveAllFlags();
                        ps.reso.instaeclipse.utils.feature.FeatureManager.refreshFeatureStatus();
                        Handler mainHandler = new Handler(Looper.getMainLooper());
                        mainHandler.post(() -> Toast.makeText(ctx.getApplicationContext(),
                                "✅ " + I18n.t(ctx, R.string.ig_toast_settings_restored), Toast.LENGTH_SHORT).show());
                    } catch (Exception e) {
                        Handler mainHandler = new Handler(Looper.getMainLooper());
                        mainHandler.post(() -> Toast.makeText(ctx.getApplicationContext(),
                                "❌ " + I18n.t(ctx, R.string.ig_toast_restore_failed, e.getMessage()), Toast.LENGTH_LONG).show());
                    }
                }).start();
            }
        };
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver,
                        new IntentFilter("ps.reso.instaeclipse.ACTION_RESTORE_SETTINGS"),
                        android.content.Context.RECEIVER_EXPORTED);
            } else {
                androidx.core.content.ContextCompat.registerReceiver(context,
                        receiver,
                        new IntentFilter("ps.reso.instaeclipse.ACTION_RESTORE_SETTINGS"),
                        androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
            }
            } catch (Throwable e) {
            ModuleLog.line("(InstaEclipse | RestoreReceiver): ❌ " + e.getMessage());
        }
    }

}
