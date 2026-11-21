package com.webhtml.app;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
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
    private boolean wasInPip = false;

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
        if (isInPictureInPictureMode) {
            wasInPip = true;
            if (overlayWebView != null) {
                overlayWebView.setVisibility(View.GONE);
            }
            if (webView != null) {
                webView.onResume();
                webView.resumeTimers();
            }
        } else if (wasInPip) {
            wasInPip = false;
            boolean closingPip = activity != null && (activity.isFinishing() || activity.isDestroyed());
            if (closingPip) {
                if (webView != null) {
                    webView.evaluateJavascript(
                            "(function() {" +
                            "  try {" +
                            "    document.querySelectorAll('video, audio').forEach(function(m) { try { m.pause(); } catch(e) {} });" +
                            "  } catch(e) {}" +
                            "})();",
                            null
                    );
                }
                try {
                    activity.stopService(new Intent(activity, MediaService.class));
                } catch (Exception ignored) {}
            } else if (webView != null) {
                webView.onResume();
                webView.resumeTimers();
            }
        }
    }
}
