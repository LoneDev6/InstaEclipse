package ps.reso.instaeclipse.mods.misc;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;

public final class LowMemoryModeHook {
    private static final int MAX_MEMORY_CLASS_MB = 128;
    private static final int MAX_PROFILE_IMAGE_EDGE = 512;
    private static final Pattern VIDEO_EXTENSION = Pattern.compile(".*\\.(mp4|m4v|webm|mkv|3gp|ts)$");
    private static final AtomicBoolean CLEANING = new AtomicBoolean();

    public void install(Context context) {
        hookMemoryProfile();
        context.getApplicationContext().registerComponentCallbacks(new ComponentCallbacks2() {
            @Override
            public void onTrimMemory(int level) {
                if (level == TRIM_MEMORY_UI_HIDDEN) cleanCacheAsync(context);
            }

            @Override public void onConfigurationChanged(Configuration newConfig) {}
            @Override public void onLowMemory() {}
        });
        cleanCacheAsync(context);
    }

    private static void hookMemoryProfile() {
        XposedBridge.hookAllMethods(ActivityManager.class, "isLowRamDevice", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.lowMemoryMode) param.setResult(true);
            }
        });

        XC_MethodHook memoryClassHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (FeatureFlags.lowMemoryMode && param.getResult() instanceof Integer) {
                    param.setResult(Math.min((Integer) param.getResult(), MAX_MEMORY_CLASS_MB));
                }
            }
        };
        XposedBridge.hookAllMethods(ActivityManager.class, "getMemoryClass", memoryClassHook);
        XposedBridge.hookAllMethods(ActivityManager.class, "getLargeMemoryClass", memoryClassHook);
    }

    private static void cleanCacheAsync(Context context) {
        if (!FeatureFlags.lowMemoryMode || !CLEANING.compareAndSet(false, true)) return;
        File cacheDir = context.getCacheDir();
        new Thread(() -> {
            try {
                deleteContents(cacheDir);
            } finally {
                CLEANING.set(false);
            }
        }, "InstaEclipse-cache-cleaner").start();
    }

    static void deleteContents(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
                deleteContents(file);
                file.delete();
            } else if (shouldDeleteMedia(file)) {
                file.delete();
            }
        }
    }

    private static boolean shouldDeleteMedia(File file) {
        String path = file.getAbsolutePath().toLowerCase(Locale.ROOT);
        if (VIDEO_EXTENSION.matcher(path).matches()
                || path.contains("/video") || path.contains("/reel")
                || path.contains("/clips") || path.contains("/preview")) return true;
        if (hasVideoHeader(file)) return true;

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        return bounds.outWidth > 0 && !isLikelyProfileImage(bounds.outWidth, bounds.outHeight);
    }

    // ponytail: square thumbnails can be mistaken for avatars; use URL-aware cache hooks if this matters.
    static boolean isLikelyProfileImage(int width, int height) {
        return width == height && width <= MAX_PROFILE_IMAGE_EDGE;
    }

    private static boolean hasVideoHeader(File file) {
        byte[] header = new byte[12];
        try (FileInputStream input = new FileInputStream(file)) {
            int read = input.read(header);
            return (read >= 8 && header[4] == 'f' && header[5] == 't'
                    && header[6] == 'y' && header[7] == 'p'
                    || read >= 4 && (header[0] & 0xff) == 0x1a && (header[1] & 0xff) == 0x45
                    && (header[2] & 0xff) == 0xdf && (header[3] & 0xff) == 0xa3);
        } catch (IOException ignored) {
            return false;
        }
    }
}
