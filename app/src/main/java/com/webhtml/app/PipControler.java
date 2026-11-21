package com.webhtml.app;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Rational;
import android.view.View;
import android.webkit.WebView;

public class PipControler {

    public enum PipMode {
        OFF,
        SQUARE,   // 1:1 -> pipModeOn: true
        PORTRAIT  // 9:16 -> pipModeOn: "true"
    }

    private final Activity activity;
    private PipMode pipMode = PipMode.OFF;
    private boolean suppressNextLeaveHint = false;

    public PipControler(Activity activity, Object pipModeValue) {
        this.activity = activity;
        setPipModeOn(pipModeValue);
    }

    public void setPipModeOn(Object value) {
        if (value == null) {
            this.pipMode = PipMode.OFF;
            return;
        }

        if (value instanceof Boolean) {
            this.pipMode = ((Boolean) value) ? PipMode.SQUARE : PipMode.OFF;
            return;
        }

        if (value instanceof String) {
            String s = ((String) value).trim().toLowerCase();
            if ("true".equals(s)) {
                this.pipMode = PipMode.PORTRAIT;
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
        return pipMode != PipMode.OFF;
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
            try {
                PictureInPictureParams.Builder paramsBuilder = new PictureInPictureParams.Builder();
                if (pipMode == PipMode.PORTRAIT) {
                    paramsBuilder.setAspectRatio(new Rational(9, 16));
                } else {
                    paramsBuilder.setAspectRatio(new Rational(1, 1));
                }
                activity.enterPictureInPictureMode(paramsBuilder.build());
            } catch (Exception ignored) {}
        }
    }

    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, View overlayWebView, WebView webView) {
        if (overlayWebView != null && isInPictureInPictureMode) {
            overlayWebView.setVisibility(View.GONE);
        }
        if (webView != null && isInPictureInPictureMode) {
            webView.onResume();
            webView.resumeTimers();
        }
        applyVideoFocus(isInPictureInPictureMode, webView);
    }

    private void applyVideoFocus(boolean enterPip, WebView webView) {
        if (webView == null) return;
        String currentUrl = webView.getUrl();
        if (currentUrl != null && (currentUrl.contains("youtube.com") || currentUrl.contains("youtu.be"))) {
            return;
        }

        String js = enterPip
            ? "(function() {" +
              "  try {" +
              "    var videos = Array.from(document.querySelectorAll('video'));" +
              "    if (!videos.length) return;" +
              "    videos.sort(function(a, b) { return (b.clientWidth * b.clientHeight) - (a.clientWidth * a.clientHeight); });" +
              "    var v = videos[0];" +
              "    var target = (v.parentElement && v.parentElement.clientWidth >= window.innerWidth * 0.7) ? v.parentElement : v;" +
              "    window.__pipSaved = { el: target, style: target.getAttribute('style') || '', overflow: document.documentElement.style.overflow };" +
              "    document.documentElement.style.overflow = 'hidden';" +
              "    target.style.cssText = 'position:fixed !important;top:0 !important;left:0 !important;width:100vw !important;height:100vh !important;max-width:100vw !important;max-height:100vh !important;z-index:2147483647 !important;background:#000 !important;margin:0 !important;padding:0 !important;';" +
              "    v.style.cssText = 'width:100% !important;height:100% !important;object-fit:contain !important;background:#000 !important;';" +
              "  } catch(e) {}" +
              "})();"
            : "(function() {" +
              "  try {" +
              "    if (window.__pipSaved) {" +
              "      var s = window.__pipSaved;" +
              "      if (s.el) { if (s.style) s.el.setAttribute('style', s.style); else s.el.removeAttribute('style'); }" +
              "      document.documentElement.style.overflow = s.overflow || '';" +
              "      var v = s.el ? s.el.querySelector('video') : null;" +
              "      if (v) v.removeAttribute('style');" +
              "      window.__pipSaved = null;" +
              "    }" +
              "  } catch(e) {}" +
              "})();";

        webView.evaluateJavascript(js, null);
    }
}
