package com.webhtml.app;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;

import org.json.JSONObject;

public class PipControler {

    private static final String PREFS_NAME = "pip_controler_prefs";
    private static final String KEY_LAST_PORTRAIT = "last_was_portrait";
    private static final String KEY_LAST_RATIO_NUM = "last_ratio_num";
    private static final String KEY_LAST_RATIO_DEN = "last_ratio_den";

    public enum PipMode {
        OFF,        // false
        AUTO        // true - automatski bira portrait ili landscape prema videu
    }

    public static class DimMaskView extends View {
        private final Paint dimPaint = new Paint();
        private final RectF videoRect = new RectF();
        private boolean hasVideoRect = false;

        public DimMaskView(Context context) {
            super(context);
            dimPaint.setColor(Color.parseColor("#FF000000"));
            setClickable(false);
            setFocusable(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setElevation(100f);
            }
        }

        public void setVideoRect(float left, float top, float right, float bottom) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                hasVideoRect = false;
                invalidate();
                return;
            }

            float clampedLeft = Math.max(0f, Math.min(left, w));
            float clampedTop = Math.max(0f, Math.min(top, h));
            float clampedRight = Math.max(clampedLeft, Math.min(right, w));
            float clampedBottom = Math.max(clampedTop, Math.min(bottom, h));

            videoRect.set(clampedLeft, clampedTop, clampedRight, clampedBottom);
            hasVideoRect = (videoRect.width() > 4f && videoRect.height() > 4f);
            invalidate();
        }

        public void clearVideoRect() {
            hasVideoRect = false;
            invalidate();
        }

        public Rect getVideoRectAsIntRect() {
            if (!hasVideoRect) return null;
            return new Rect(
                    Math.round(videoRect.left),
                    Math.round(videoRect.top),
                    Math.round(videoRect.right),
                    Math.round(videoRect.bottom)
            );
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getVisibility() != VISIBLE) return;

            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            if (!hasVideoRect) {
                canvas.drawRect(0, 0, w, h, dimPaint);
                return;
            }

            if (videoRect.top > 0) {
                canvas.drawRect(0, 0, w, videoRect.top, dimPaint);
            }
            if (videoRect.bottom < h) {
                canvas.drawRect(0, videoRect.bottom, w, h, dimPaint);
            }
            if (videoRect.left > 0) {
                canvas.drawRect(0, videoRect.top, videoRect.left, videoRect.bottom, dimPaint);
            }
            if (videoRect.right < w) {
                canvas.drawRect(videoRect.right, videoRect.top, w, videoRect.bottom, dimPaint);
            }
        }
    }

    private static final String JS_FIND_LARGEST_VIDEO_NORM =
            "(function() {" +
            "  try {" +
            "    var vw = window.innerWidth || document.documentElement.clientWidth || 1;" +
            "    var vh = window.innerHeight || document.documentElement.clientHeight || 1;" +
            "    var videos = Array.from(document.querySelectorAll('video'));" +
            "    var best = null, bestRect = null, bestArea = 0;" +
            "    for (var i = 0; i < videos.length; i++) {" +
            "      var r = videos[i].getBoundingClientRect();" +
            "      if (r.width <= 20 || r.height <= 20) continue;" +
            "      var area = r.width * r.height;" +
            "      if (area >= bestArea) { bestArea = area; best = videos[i]; bestRect = r; }" +
            "    }" +
            "    if (!best || !bestRect) return null;" +
            "    var mediaW = (best.videoWidth && best.videoWidth > 0) ? best.videoWidth : bestRect.width;" +
            "    var mediaH = (best.videoHeight && best.videoHeight > 0) ? best.videoHeight : bestRect.height;" +
            "    return JSON.stringify({" +
            "      nx1: bestRect.left / vw," +
            "      ny1: bestRect.top / vh," +
            "      nx2: bestRect.right / vw," +
            "      ny2: bestRect.bottom / vh," +
            "      width: mediaW," +
            "      height: mediaH," +
            "      isPortrait: mediaH > mediaW" +
            "    });" +
            "  } catch (e) { return null; }" +
            "})();";

    private static final String JS_ISOLATE_VIDEO_FOR_PIP =
            "(function() {" +
            "  try {" +
            "    var videos = Array.from(document.querySelectorAll('video'));" +
            "    var best = null, bestArea = 0;" +
            "    for (var i = 0; i < videos.length; i++) {" +
            "      var r = videos[i].getBoundingClientRect();" +
            "      if (r.width <= 20 || r.height <= 20) continue;" +
            "      var area = r.width * r.height;" +
            "      if (area >= bestArea) { bestArea = area; best = videos[i]; }" +
            "    }" +
            "    if (!best) return false;" +
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
            "    return true;" +
            "  } catch (e) { return false; }" +
            "})();";

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
    private DimMaskView dimView;
    private WebView attachedWebView;
    private Rational detectedAspectRatio = null;
    private boolean detectedPortraitVideo = false;
    private Bitmap lastMotionSample = null;

    private final Runnable refreshMaskRunnable = () -> {
        if (wasInPip && attachedWebView != null) {
            applyVideoMaskFromWebView(attachedWebView);
        }
    };

    private final Runnable refreshMaskDelayedRunnable = () -> {
        if (wasInPip && attachedWebView != null) {
            applyVideoMaskFromWebView(attachedWebView);
        }
    };

    private final View.OnLayoutChangeListener layoutChangeListener = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
        if (wasInPip && attachedWebView != null) {
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                scheduleMaskRefresh();
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

    private void scheduleMaskRefresh() {
        mainHandler.removeCallbacks(refreshMaskRunnable);
        mainHandler.removeCallbacks(refreshMaskDelayedRunnable);
        mainHandler.postDelayed(refreshMaskRunnable, 40);
        mainHandler.postDelayed(refreshMaskDelayedRunnable, 250);
    }

    public void attachTo(WebView webView) {
        if (webView == null) return;
        this.attachedWebView = webView;
        updateVideoAspectFromWebView(webView);

        if (dimView == null) {
            dimView = new DimMaskView(webView.getContext());
            dimView.setVisibility(View.GONE);
            dimView.addOnLayoutChangeListener(layoutChangeListener);
        }
        webView.removeOnLayoutChangeListener(layoutChangeListener);
        webView.addOnLayoutChangeListener(layoutChangeListener);

        if (dimView.getParent() != null) {
            return;
        }
        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent == null) return;

        if (parent instanceof FrameLayout) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            parent.addView(dimView, lp);
            dimView.bringToFront();
        } else {
            int index = parent.indexOfChild(webView);
            FrameLayout wrapper = new FrameLayout(webView.getContext());
            wrapper.setLayoutParams(webView.getLayoutParams());
            parent.removeViewAt(index);
            wrapper.addView(webView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            wrapper.addView(dimView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            parent.addView(wrapper, index);
            dimView.bringToFront();
        }
    }

    public void setDimView(DimMaskView dimView) {
        this.dimView = dimView;
        if (this.dimView != null) {
            this.dimView.removeOnLayoutChangeListener(layoutChangeListener);
            this.dimView.addOnLayoutChangeListener(layoutChangeListener);
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
                    boolean changed = (portrait != detectedPortraitVideo)
                            || (detectedAspectRatio == null)
                            || (Math.abs(clamped.floatValue() - detectedAspectRatio.floatValue()) > 0.05f);
                    detectedPortraitVideo = portrait;
                    detectedAspectRatio = clamped;
                    saveCurrentMode(portrait, clamped);

                    if (changed && wasInPip && !activity.isFinishing() && !activity.isDestroyed()) {
                        try {
                            PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
                            b.setAspectRatio(resolveAspectRatio());
                            activity.setPictureInPictureParams(b.build());
                        } catch (Exception ignored) {}
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
            if (attachedWebView != null) {
                updateVideoAspectFromWebView(attachedWebView);
            }
            try {
                PictureInPictureParams.Builder paramsBuilder = new PictureInPictureParams.Builder();
                paramsBuilder.setAspectRatio(resolveAspectRatio());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dimView != null) {
                    Rect hint = dimView.getVideoRectAsIntRect();
                    if (hint != null) {
                        paramsBuilder.setSourceRectHint(hint);
                    }
                }
                activity.enterPictureInPictureMode(paramsBuilder.build());
            } catch (Exception ignored) {}
        }
    }

    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, View overlayWebView, WebView webView) {
        if (isInPictureInPictureMode) {
            wasInPip = true;
            attachTo(webView);
            resetWebViewTransform(webView);
            if (overlayWebView != null) {
                overlayWebView.setVisibility(View.GONE);
            }
            if (webView != null) {
                webView.onResume();
                webView.resumeTimers();
            }
            if (dimView != null) {
                dimView.clearVideoRect();
                dimView.setVisibility(View.GONE);
            }
            scheduleMaskRefresh();
        } else if (wasInPip) {
            wasInPip = false;
            mainHandler.removeCallbacks(refreshMaskRunnable);
            mainHandler.removeCallbacks(refreshMaskDelayedRunnable);
            if (lastMotionSample != null && !lastMotionSample.isRecycled()) {
                lastMotionSample.recycle();
                lastMotionSample = null;
            }
            resetWebViewTransform(webView);
            if (dimView != null) {
                dimView.clearVideoRect();
                dimView.setVisibility(View.GONE);
            }
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
            }
            if (activity != null && (activity.isFinishing() || activity.isDestroyed())) {
                try {
                    activity.stopService(new Intent(activity, MediaService.class));
                } catch (Exception ignored) {}
            }
        }
    }

    private void applyVideoMaskFromWebView(WebView webView) {
        if (!wasInPip || webView == null) return;
        resetWebViewTransform(webView);
        updateVideoAspectFromWebView(webView);
        webView.evaluateJavascript(JS_ISOLATE_VIDEO_FOR_PIP, result -> {
            if (!wasInPip) return;
            boolean isolated = "true".equalsIgnoreCase(String.valueOf(result).replace("\"", "").trim());
            if (!isolated && dimView != null) {
                dimView.setVisibility(View.VISIBLE);
                dimView.clearVideoRect();
                dimView.bringToFront();
                dimView.invalidate();
            }
        });
    }
}
