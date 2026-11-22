package com.webhtml.app;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.View;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.Locale;

public class PipControler {

    private static final String PREFS_NAME = "pip_controler_prefs";
    private static final String KEY_LAST_PORTRAIT = "last_was_portrait";
    private static final String KEY_LAST_RATIO_NUM = "last_ratio_num";
    private static final String KEY_LAST_RATIO_DEN = "last_ratio_den";

    public enum PipMode {
        OFF,
        AUTO
    }

    // Prioritet dajemo aktivnom (ne-pauziranom) i vidljivom videu najveće površine
    private static final String JS_FIND_LARGEST_VIDEO_NORM =
            "(function() {" +
            "  try {" +
            "    var videos = Array.from(document.querySelectorAll('video'));" +
            "    var best = null, bestRect = null, bestScore = -1;" +
            "    for (var i = 0; i < videos.length; i++) {" +
            "      var v = videos[i];" +
            "      var r = v.getBoundingClientRect();" +
            "      if (r.width <= 20 || r.height <= 20) continue;" +
            "      var activeBoost = (!v.paused && !v.ended && v.readyState >= 2) ? 1000000 : 1;" +
            "      var score = activeBoost * (r.width * r.height);" +
            "      if (score >= bestScore) { bestScore = score; best = v; bestRect = r; }" +
            "    }" +
            "    if (!best || !bestRect) return null;" +
            "    var mediaW = (best.videoWidth && best.videoWidth > 0) ? best.videoWidth : bestRect.width;" +
            "    var mediaH = (best.videoHeight && best.videoHeight > 0) ? best.videoHeight : bestRect.height;" +
            "    if (!mediaW || !mediaH) return null;" +
            "    return JSON.stringify({" +
            "      width: mediaW," +
            "      height: mediaH," +
            "      isPortrait: mediaH > mediaW" +
            "    });" +
            "  } catch (e) { return null; }" +
            "})();";

    private static final String JS_ISOLATE_IF_PLAYING =
            "(function() {" +
            "  try {" +
            "    var videos = Array.from(document.querySelectorAll('video'));" +
            "    var best = null, bestArea = 0;" +
            "    for (var i = 0; i < videos.length; i++) {" +
            "      var v = videos[i];" +
            "      var r = v.getBoundingClientRect();" +
            "      if (r.width <= 20 || r.height <= 20) continue;" +
            "      if (v.paused || v.ended || v.readyState < 2) continue;" +
            "      var area = r.width * r.height;" +
            "      if (area >= bestArea) { bestArea = area; best = v; }" +
            "    }" +
            "    if (!best) return JSON.stringify({ ok: false, t: -1 });" +
            "    if (best.getAttribute('data-pip-prev-style') === null) {" +
            "      best.setAttribute('data-pip-prev-style', best.getAttribute('style') || '');" +
            "    }" +
            "    best.style.setProperty('position', 'fixed', 'important');" +
            "    best.style.setProperty('top', '0', 'important');" +
            "    best.style.setProperty('left', '0', 'important');" +
            "    best.style.setProperty('width', '100vw', 'important');" +
            "    best.style.setProperty('height', '100vh', 'important');" +
            "    best.style.setProperty('max-width', '100vw', 'important');" +
            "    best.style.setProperty('max-height', '100vh', 'important');" +
            "    best.style.setProperty('object-fit', 'contain', 'important');" +
            "    best.style.setProperty('z-index', '2147483647', 'important');" +
            "    best.style.setProperty('background', '#000000', 'important');" +
            "    return JSON.stringify({ ok: true, t: best.currentTime || 0 });" +
            "  } catch (e) { return JSON.stringify({ ok: false, t: -1 }); }" +
            "})();";

    private static final String JS_VERIFY_VIDEO_MOVING =
            "(function(prevT) {" +
            "  try {" +
            "    var v = document.querySelector('video[data-pip-prev-style]');" +
            "    if (!v) return false;" +
            "    if (v.paused || v.ended || v.readyState < 2) return false;" +
            "    return (v.currentTime || 0) > prevT + 0.01;" +
            "  } catch (e) { return false; }" +
            "})(%s);";

    private static final String JS_RESTORE_VIDEO_FROM_PIP =
            "(function() {" +
            "  try {" +
            "    document.querySelectorAll('video[data-pip-prev-style]').forEach(function(v) {" +
            "      var prev = v.getAttribute('data-pip-prev-style');" +
            "      if (prev === '') {" +
            "        v.removeAttribute('style');" +
            "      } else {" +
            "        v.setAttribute('style', prev);" +
            "      }" +
            "      v.removeAttribute('data-pip-prev-style');" +
            "    });" +
            "  } catch (e) {}" +
            "})();";

    private final Activity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PipMode pipMode = PipMode.OFF;
    private boolean suppressNextLeaveHint = false;
    private boolean wasInPip = false;
    private WebView attachedWebView;
    private Rational detectedAspectRatio = null;
    private boolean detectedPortraitVideo = false;
    private Rational lastAppliedPipRatio = null;

    private final Runnable refreshVideoRunnable = () -> {
        if (wasInPip && attachedWebView != null) {
            tryIsolateActiveVideo(attachedWebView);
        }
    };

    private final Runnable periodicAspectSyncRunnable = new Runnable() {
        @Override
        public void run() {
            if (attachedWebView != null && !activity.isFinishing() && !activity.isDestroyed()) {
                refreshAspectAsync(attachedWebView, wasInPip);
                mainHandler.postDelayed(this, wasInPip ? 600 : 1200);
            }
        }
    };

    private final View.OnLayoutChangeListener layoutChangeListener = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
        if (attachedWebView != null && (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom)) {
            refreshAspectAsync(attachedWebView, wasInPip);
            if (wasInPip) {
                scheduleVideoRefresh();
            }
        }
    };

    public PipControler(Activity activity, Object pipModeValue) {
        this.activity = activity;
        loadSavedMode();
        setPipModeOn(pipModeValue);
    }

    private SharedPreferences getPrefs() {
        if (activity == null) return null;
        return activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void loadSavedMode() {
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return;
        detectedPortraitVideo = prefs.getBoolean(KEY_LAST_PORTRAIT, false);
        int num = prefs.getInt(KEY_LAST_RATIO_NUM, -1);
        int den = prefs.getInt(KEY_LAST_RATIO_DEN, -1);
        if (num > 0 && den > 0) {
            detectedAspectRatio = new Rational(num, den);
        } else {
            detectedAspectRatio = detectedPortraitVideo ? new Rational(9, 16) : new Rational(16, 9);
        }
    }

    private void saveCurrentMode(boolean portrait, Rational ratio) {
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return;
        SharedPreferences.Editor editor = prefs.edit().putBoolean(KEY_LAST_PORTRAIT, portrait);
        if (ratio != null && ratio.getNumerator() > 0 && ratio.getDenominator() > 0) {
            editor.putInt(KEY_LAST_RATIO_NUM, ratio.getNumerator())
                  .putInt(KEY_LAST_RATIO_DEN, ratio.getDenominator());
        }
        editor.apply();
    }

    private void resetWebViewTransform(WebView webView) {
        if (webView == null) return;
        webView.setScaleX(1f);
        webView.setScaleY(1f);
        webView.setTranslationX(0f);
        webView.setTranslationY(0f);
    }

    private void scheduleVideoRefresh() {
        mainHandler.removeCallbacks(refreshVideoRunnable);
        mainHandler.postDelayed(refreshVideoRunnable, 120);
    }

    private void startAspectSyncLoop() {
        mainHandler.removeCallbacks(periodicAspectSyncRunnable);
        mainHandler.post(periodicAspectSyncRunnable);
    }

    private void stopAspectSyncLoop() {
        mainHandler.removeCallbacks(periodicAspectSyncRunnable);
    }

    public void attachTo(WebView webView) {
        if (webView == null) return;
        this.attachedWebView = webView;
        webView.removeOnLayoutChangeListener(layoutChangeListener);
        webView.addOnLayoutChangeListener(layoutChangeListener);
        refreshAspectAsync(webView, wasInPip);
        startAspectSyncLoop();
    }

    public void detach() {
        stopAspectSyncLoop();
        if (attachedWebView != null) {
            attachedWebView.removeOnLayoutChangeListener(layoutChangeListener);
            attachedWebView = null;
        }
    }

    public void setPipModeOn(Object value) {
        if (value == null) {
            this.pipMode = PipMode.OFF;
            return;
        }

        if (value instanceof PipMode) {
            this.pipMode = (PipMode) value;
            return;
        }

        if (value instanceof Boolean) {
            this.pipMode = ((Boolean) value) ? PipMode.AUTO : PipMode.OFF;
            return;
        }

        if (value instanceof String) {
            String s = ((String) value).trim().toLowerCase();
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
                s = s.substring(1, s.length() - 1).trim();
            }
            if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "on".equals(s)) {
                this.pipMode = PipMode.AUTO;
            } else {
                this.pipMode = PipMode.OFF;
            }
            return;
        }

        this.pipMode = PipMode.OFF;
    }

    public PipMode getPipMode() {
        return pipMode;
    }

    public boolean isPipEnabled() {
        return pipMode == PipMode.AUTO;
    }

    public boolean isDetectedPortraitVideo() {
        return detectedPortraitVideo;
    }

    public void suppressNextLeaveHint() {
        this.suppressNextLeaveHint = true;
    }

    public void resetSuppressLeaveHint() {
        this.suppressNextLeaveHint = false;
    }

    public boolean isPipSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        return activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    public void updateVideoAspectFromWebView(WebView webView) {
        refreshAspectAsync(webView, wasInPip);
    }

    private void applyPipAspectRatioIfNeeded(Rational ratio, boolean forceUpdate) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity == null || ratio == null) return;
        if (!activity.isInPictureInPictureMode()) return;
        if (!forceUpdate && ratio.equals(lastAppliedPipRatio)) return;
        try {
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(ratio)
                    .build();
            activity.setPictureInPictureParams(params);
            lastAppliedPipRatio = ratio;
        } catch (Exception ignored) {}
    }

    private void refreshAspectAsync(WebView webView, boolean applyToActivePip) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || webView == null) return;
        webView.evaluateJavascript(JS_FIND_LARGEST_VIDEO_NORM, result -> {
            if (result == null || "null".equals(result) || "\"null\"".equals(result)) {
                return;
            }
            try {
                String clean = result.trim();
                if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length() >= 2) {
                    clean = clean.substring(1, clean.length() - 1)
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\");
                }
                JSONObject obj = new JSONObject(clean);
                double w = obj.optDouble("width", 0);
                double h = obj.optDouble("height", 0);
                if (w > 0 && h > 0) {
                    boolean portrait = obj.optBoolean("isPortrait", h > w);
                    Rational clamped = clampPipAspectRatio(w, h);
                    boolean orientationOrRatioChanged = (portrait != detectedPortraitVideo)
                            || (detectedAspectRatio == null || !clamped.equals(detectedAspectRatio));
                    detectedPortraitVideo = portrait;
                    detectedAspectRatio = clamped;
                    saveCurrentMode(portrait, clamped);

                    if (applyToActivePip) {
                        applyPipAspectRatioIfNeeded(clamped, orientationOrRatioChanged);
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private Rational clampPipAspectRatio(double width, double height) {
        double ratio = width / height;
        double minRatio = 1.0 / 2.39;
        double maxRatio = 2.39;
        if (ratio < minRatio) ratio = minRatio;
        if (ratio > maxRatio) ratio = maxRatio;

        int num = (int) Math.round(ratio * 1000);
        int den = 1000;
        return new Rational(num, den);
    }

    private Rational resolveAspectRatio() {
        if (detectedAspectRatio != null) {
            return detectedAspectRatio;
        }
        return detectedPortraitVideo ? new Rational(9, 16) : new Rational(16, 9);
    }

    public void onUserLeaveHint() {
        if (suppressNextLeaveHint) {
            suppressNextLeaveHint = false;
            return;
        }

        if (!isPipEnabled() || !isPipSupported()) {
            return;
        }

        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (activity.isInPictureInPictureMode()) {
                return;
            }
            Rational ratio = resolveAspectRatio();
            lastAppliedPipRatio = ratio;
            try {
                PictureInPictureParams.Builder paramsBuilder = new PictureInPictureParams.Builder();
                paramsBuilder.setAspectRatio(ratio);
                activity.enterPictureInPictureMode(paramsBuilder.build());
            } catch (Exception ignored) {}
            if (attachedWebView != null) {
                refreshAspectAsync(attachedWebView, true);
            }
        }
    }

    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, View overlayWebView, WebView webView) {
        if (isInPictureInPictureMode) {
            wasInPip = true;
            this.attachedWebView = webView;
            resetWebViewTransform(webView);
            if (overlayWebView != null) {
                overlayWebView.setVisibility(View.GONE);
            }
            if (webView != null) {
                webView.onResume();
                webView.resumeTimers();
                refreshAspectAsync(webView, true);
            }
            startAspectSyncLoop();
            scheduleVideoRefresh();
        } else if (wasInPip) {
            wasInPip = false;
            lastAppliedPipRatio = null;
            mainHandler.removeCallbacks(refreshVideoRunnable);
            resetWebViewTransform(webView);
            if (webView != null) {
                webView.evaluateJavascript(
                        JS_RESTORE_VIDEO_FROM_PIP +
                        "(function() {" +
                        "  try {" +
                        "    document.querySelectorAll('video, audio').forEach(function(m) { try { m.pause(); } catch(e) {} });" +
                        "  } catch(e) {}" +
                        "})();",
                        null
                );
                refreshAspectAsync(webView, false);
            }
            startAspectSyncLoop();
            if (activity != null && (activity.isFinishing() || activity.isDestroyed())) {
                stopAspectSyncLoop();
                try {
                    activity.stopService(new Intent(activity, MediaService.class));
                } catch (Exception ignored) {}
            }
        }
    }

    private void useDefaultPipFallback(WebView webView) {
        if (webView == null) return;
        resetWebViewTransform(webView);
        webView.evaluateJavascript(JS_RESTORE_VIDEO_FROM_PIP, null);
    }

    private void tryIsolateActiveVideo(WebView webView) {
        if (!wasInPip || webView == null) return;
        resetWebViewTransform(webView);

        webView.evaluateJavascript(JS_ISOLATE_IF_PLAYING, raw -> {
            if (!wasInPip || attachedWebView == null) return;
            try {
                String clean = raw == null ? "" : raw.trim();
                if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length() >= 2) {
                    clean = clean.substring(1, clean.length() - 1)
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\");
                }
                JSONObject obj = new JSONObject(clean);
                boolean ok = obj.optBoolean("ok", false);
                double startTime = obj.optDouble("t", -1);
                if (!ok || startTime < 0) {
                    useDefaultPipFallback(attachedWebView);
                    return;
                }

                mainHandler.postDelayed(() -> {
                    if (!wasInPip || attachedWebView == null) return;
                    String verifyJs = String.format(Locale.US, JS_VERIFY_VIDEO_MOVING, startTime);
                    attachedWebView.evaluateJavascript(verifyJs, movingRaw -> {
                        if (!wasInPip || attachedWebView == null) return;
                        boolean moving = "true".equalsIgnoreCase(String.valueOf(movingRaw).replace("\"", "").trim());
                        if (!moving) {
                            useDefaultPipFallback(attachedWebView);
                        }
                    });
                }, 220);
            } catch (Exception e) {
                useDefaultPipFallback(attachedWebView);
            }
        });
    }
}
