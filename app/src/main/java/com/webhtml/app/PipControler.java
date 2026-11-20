package com.webhtml.app;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Rational;
import android.view.View;
import android.webkit.WebView;

public class PipControler {

    private final Activity activity;
    private String pipMode;
    private boolean suppressNextLeaveHint = false;

    public PipControler(Activity activity, String pipMode) {
        this.activity = activity;
        setPipModeOn(pipMode);
    }

    public void setPipModeOn(String pipMode) {
        if (pipMode == null) {
            this.pipMode = "false";
            return;
        }
        String normalized = pipMode.trim().toLowerCase();
        if ("default".equals(normalized) || "portrait".equals(normalized)) {
            this.pipMode = normalized;
        } else {
            this.pipMode = "false";
        }
    }

    public String getPipModeOn() {
        return pipMode;
    }

    public boolean isPipEnabled() {
        return "default".equals(pipMode) || "portrait".equals(pipMode);
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
                if ("portrait".equals(pipMode)) {
                    paramsBuilder.setAspectRatio(new Rational(9, 16));
                } else {
                    // "default" -> kvadrat
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
    }
}
