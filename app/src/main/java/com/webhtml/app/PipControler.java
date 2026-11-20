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
    private boolean pipModeOn;
    private boolean suppressNextLeaveHint = false;

    public PipControler(Activity activity, boolean pipModeOn) {
        this.activity = activity;
        this.pipModeOn = pipModeOn;
    }

    public void setPipModeOn(boolean pipModeOn) {
        this.pipModeOn = pipModeOn;
    }

    public boolean isPipModeOn() {
        return pipModeOn;
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

        if (!pipModeOn || !isPipSupported()) {
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
                paramsBuilder.setAspectRatio(new Rational(1, 1));
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
        }
    }
}
