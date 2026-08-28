package ps.reso.instaeclipse.mods.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewConfiguration;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.log.ModuleLog;

final class WatchHistoryBulkDeleteHook {
    private static final int LIMIT = 100;
    private static final WeakHashMap<Activity, Boolean> RUNNING = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Boolean> AUTOMATIC = new WeakHashMap<>();
    private static final WeakHashMap<Activity, String> SCREEN_TITLES = new WeakHashMap<>();
    private static volatile boolean INSTALLED;

    private WatchHistoryBulkDeleteHook() {}

    static void install() {
        if (INSTALLED) return;
        INSTALLED = true;
        Class<?> activityClass = XposedHelpers.findClass(
                "com.instagram.base.activity.IgFragmentActivity", Module.hostClassLoader);
        XposedBridge.hookAllMethods(activityClass, "dispatchTouchEvent", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.args[0] instanceof MotionEvent)
                        || ((MotionEvent) param.args[0]).getActionMasked() != MotionEvent.ACTION_UP) {
                    return;
                }
                Activity activity = (Activity) param.thisObject;
                TextView title = findScreenTitle(activity.getWindow().getDecorView());
                param.setObjectExtra("instaeclipse_watch_history",
                        title != null && isSelectTap(activity, (MotionEvent) param.args[0]));
                param.setObjectExtra("instaeclipse_watch_history_title", title);
                param.setObjectExtra("instaeclipse_watch_history_long",
                        ((MotionEvent) param.args[0]).getEventTime()
                                - ((MotionEvent) param.args[0]).getDownTime()
                                >= ViewConfiguration.getLongPressTimeout());
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!Boolean.TRUE.equals(param.getObjectExtra("instaeclipse_watch_history"))) return;
                Activity activity = (Activity) param.thisObject;
                if (Boolean.TRUE.equals(RUNNING.put(activity, true))) return;
                TextView title = (TextView) param.getObjectExtra(
                        "instaeclipse_watch_history_title");
                SCREEN_TITLES.put(activity, title.getText().toString());
                View root = activity.getWindow().getDecorView();
                if (Boolean.TRUE.equals(
                        param.getObjectExtra("instaeclipse_watch_history_long"))) {
                    root.postDelayed(() -> prepareAutomaticConfirmation(activity, 0), 120L);
                } else {
                    root.postDelayed(() -> startSelection(activity, 0), 120L);
                }
            }
        });
        ModuleLog.line("(InstaEclipse | Watch history): Select tap hook installed");
    }

    private static boolean isSelectTap(Activity activity, MotionEvent event) {
        View root = activity.getWindow().getDecorView();
        return event.getRawX() > root.getWidth() * 0.7f
                && event.getRawY() < root.getHeight() * 0.15f;
    }

    private static void confirmAutomaticDeletion(Activity activity) {
        if (!isSameScreen(activity)) {
            stop(activity);
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Eliminazione automatica")
                .setMessage("Verranno rimossi 100 elementi alla volta finché non chiudi questa schermata.")
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> stop(activity))
                .setPositiveButton("Avvia", (dialog, which) -> {
                    AUTOMATIC.put(activity, true);
                    startAutomaticBatch(activity);
                })
                .setOnCancelListener(dialog -> stop(activity))
                .show();
    }

    private static void prepareAutomaticConfirmation(Activity activity, int attempt) {
        View root = activity.getWindow().getDecorView();
        if (!isSameScreen(activity)) {
            stop(activity);
            return;
        }
        CheckBox checkBox = findCheckBox(root);
        if (checkBox != null && findScrollableAncestor(checkBox) != null) {
            confirmAutomaticDeletion(activity);
            return;
        }
        if (attempt == 0) {
            tap(root, root.getWidth() * 0.85f, root.getHeight() * 0.075f);
        }
        if (attempt < 20) {
            root.postDelayed(() -> prepareAutomaticConfirmation(activity, attempt + 1), 100L);
        } else {
            stop(activity);
        }
    }

    private static void startSelection(Activity activity, int attempt) {
        View root = activity.getWindow().getDecorView();
        CheckBox checkBox = findCheckBox(root);
        View scrollable = findScrollableAncestor(checkBox);
        if (checkBox == null || scrollable == null) {
            if (attempt < 20) {
                root.postDelayed(
                        () -> startSelection(activity, attempt + 1), 100L);
            } else if (Boolean.TRUE.equals(AUTOMATIC.get(activity))) {
                root.postDelayed(() -> startAutomaticBatch(activity), 250L);
            } else {
                stop(activity);
            }
            return;
        }
        selectNext(activity, scrollable, 0, 0);
    }

    private static void selectNext(Activity activity, View scrollable, int selected, int stalled) {
        if (!isSameScreen(activity)) {
            stop(activity);
            return;
        }
        if (selected >= LIMIT) {
            if (Boolean.TRUE.equals(AUTOMATIC.get(activity))) {
                if (findCheckBox(activity.getWindow().getDecorView()) != null) {
                    pressRemove(activity);
                } else {
                    startAutomaticBatch(activity);
                }
            } else {
                stop(activity);
            }
            return;
        }

        CheckBox checkBox = findUncheckedCheckBox(scrollable);
        if (checkBox != null) {
            View clickable = findClickableAncestor(checkBox);
            if (clickable == null || !clickable.performClick()) {
                fail(activity, "Selezione interrotta");
                return;
            }
            scrollable.postDelayed(() -> selectNext(activity, scrollable,
                    checkBox.isChecked() ? selected + 1 : selected, 0), 16L);
            return;
        }

        if (!scrollable.canScrollVertically(1) || stalled >= 5) {
            if (Boolean.TRUE.equals(AUTOMATIC.get(activity)) && selected > 0
                    && findCheckBox(activity.getWindow().getDecorView()) != null) {
                pressRemove(activity);
            } else if (Boolean.TRUE.equals(AUTOMATIC.get(activity))) {
                startAutomaticBatch(activity);
            } else {
                fail(activity, "Meno di 100 elementi disponibili");
            }
            return;
        }
        scrollable.scrollBy(0, Math.max(1, scrollable.getHeight() * 3 / 4));
        scrollable.postDelayed(() -> selectNext(activity, scrollable, selected, stalled + 1), 200L);
    }

    private static void pressRemove(Activity activity) {
        View root = activity.getWindow().getDecorView();
        if (!isSameScreen(activity)) {
            stop(activity);
            return;
        }
        tap(root, root.getWidth() / 2f, root.getHeight() * 0.94f);
        root.postDelayed(() -> startAutomaticBatch(activity), nextBatchDelayMillis());
    }

    private static void startAutomaticBatch(Activity activity) {
        View root = activity.getWindow().getDecorView();
        if (!Boolean.TRUE.equals(AUTOMATIC.get(activity)) || !isSameScreen(activity)) {
            stop(activity);
            return;
        }
        CheckBox checkBox = findCheckBox(root);
        boolean hasSelection = hasCheckedCheckBox(root);
        if (checkBox != null && !hasSelection) {
            startSelection(activity, 0);
            return;
        }
        if (hasSelection) {
            pressRemove(activity);
            return;
        }
        tap(root, root.getWidth() * 0.85f, root.getHeight() * 0.075f);
        root.postDelayed(() -> startSelection(activity, 0), 120L);
    }

    private static TextView findScreenTitle(View root) {
        if (root instanceof TextView && root.isShown()
                && ((TextView) root).getText().length() > 0) {
            int[] location = new int[2];
            root.getLocationOnScreen(location);
            if (location[0] < root.getResources().getDisplayMetrics().widthPixels * 0.7f
                    && location[1] < root.getResources().getDisplayMetrics().heightPixels * 0.11f) {
                return (TextView) root;
            }
        }
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            TextView match = findScreenTitle(group.getChildAt(i));
            if (match != null) return match;
        }
        return null;
    }

    private static CheckBox findCheckBox(View root) {
        if (root == null) return null;
        if (root instanceof CheckBox) return (CheckBox) root;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            CheckBox match = findCheckBox(group.getChildAt(i));
            if (match != null) return match;
        }
        return null;
    }

    private static CheckBox findUncheckedCheckBox(View root) {
        if (root instanceof CheckBox) {
            CheckBox checkBox = (CheckBox) root;
            return checkBox.isShown() && !checkBox.isChecked() ? checkBox : null;
        }
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            CheckBox match = findUncheckedCheckBox(group.getChildAt(i));
            if (match != null) return match;
        }
        return null;
    }

    private static boolean hasCheckedCheckBox(View root) {
        if (root instanceof CheckBox && ((CheckBox) root).isChecked()) return true;
        if (!(root instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (hasCheckedCheckBox(group.getChildAt(i))) return true;
        }
        return false;
    }

    private static View findScrollableAncestor(View view) {
        View current = view;
        while (current != null) {
            if (current.canScrollVertically(1) || current.canScrollVertically(-1)) return current;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static View findClickableAncestor(View view) {
        View current = view;
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (current.isClickable() && current.isEnabled()) return current;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static boolean isSameScreen(Activity activity) {
        String expected = SCREEN_TITLES.get(activity);
        TextView title = findScreenTitle(activity.getWindow().getDecorView());
        return expected != null && title != null && expected.contentEquals(title.getText());
    }

    static long nextBatchDelayMillis() {
        return ThreadLocalRandom.current().nextLong(1000L, 3001L);
    }

    private static void tap(View root, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 50L, MotionEvent.ACTION_UP, x, y, 0);
        root.dispatchTouchEvent(down);
        root.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private static void stop(Activity activity) {
        AUTOMATIC.remove(activity);
        RUNNING.remove(activity);
        SCREEN_TITLES.remove(activity);
    }

    private static void fail(Activity activity, String message) {
        stop(activity);
        ModuleLog.line("(InstaEclipse | Watch history): " + message);
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
