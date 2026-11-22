package com.webhtml.app;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Rational;
import android.view.View;
import android.webkit.WebView;

import org.json.JSONObject;

public class PipControler {

    public enum PipMode {
        OFF,
        SQUARE,   // 1:1 -> pipModeOn: true
        PORTRAIT  // 9:16 -> pipModeOn: "true"
    }

    private static final String JS_FIND_LARGEST_VIDEO =
            "(function() {" +
            "  try {" +
            "    var videos = Array.from(document.querySelectorAll('video'));" +
            "    if (!videos.length) return null;" +
            "    var best = null, bestArea = 0;" +
            "    for (var i = 0; i < videos.length; i++) {" +
            "      var r = videos[i].getBoundingClientRect();" +
            "      if (r.width <= 40 || r.height <= 40) continue;" +
            "      var area = r.width * r.height;" +
            "      if (area > bestArea) { bestArea = area; best = r; }" +
            "    }" +
            "    if (!best) return null;" +
            "    var dpr = window.devicePixelRatio || 1;" +
            "    return JSON.stringify({" +
            "      left: best.left * dpr," +
            "      top: best.top * dpr," +
            "      right: best.right * dpr," +
            "      bottom: best.bottom * dpr," +
            "      width: best.width," +
            "      height: best.height" +
            "    });" +
            "  } catch (e) { return null; }" +
            "})();";

    private final Activity activity;
    private PipMode pipMode = PipMode.OFF;
    private boolean suppressNextLeaveHint = false;
    private boolean wasInPip = false;
    private DimMaskView dimView;
    private Rational detectedAspectRatio = null;

    public PipControler(Activity activity, Object pipModeValue) {
        this.activity = activity;
        setPipModeOn(pipModeValue);
    }

    public void setDimView(DimMaskView dimView) {
        this.dimView = dimView;
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

    /**
     * Možeš pozvati periodično ili pre izlaska iz aktivnosti da ažuriraš odnos stranica za PiP.
     */
    public void updateVideoAspectFromWebView(WebView webView) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || webView == null) return;
        webView.evaluateJavascript(JS_FIND_LARGEST_VIDEO, result -> {
            if (result == null || "null".equals(result)) {
                detectedAspectRatio = null;
                return;
            }
            try {
                String clean = result;
                if (clean.startsWith("\"") && clean.endsWith("\"")) {
                    clean = clean.substring(1, clean.length() - 1).replace("\\\"", "\"");
                }
                JSONObject obj = new JSONObject(clean);
                double w = obj.optDouble("width", 0);
                double h = obj.optDouble("height", 0);
                if (w > 0 && h > 0) {
                    detectedAspectRatio = clampPipAspectRatio(w, h);
                }
            } catch (Exception ignored) {
                detectedAspectRatio = null;
            }
        });
    }

    private Rational clampPipAspectRatio(double width, double height) {
        double ratio = width / height;
        // Android dozvoljava otprilike [1/2.39, 2.39/1]
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
        if (pipMode == PipMode.PORTRAIT) {
            return new Rational(9, 16);
        }
        return new Rational(1, 1);
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
                paramsBuilder.setAspectRatio(resolveAspectRatio());
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
            if (dimView != null) {
                dimView.setVisibility(View.VISIBLE);
                applyVideoMaskFromWebView(webView);
            }
        } else if (wasInPip) {
            wasInPip = false;
            if (dimView != null) {
                dimView.clearVideoRect();
                dimView.setVisibility(View.GONE);
            }
            // Kada korisnik zatvori PiP prozor (npr. na X), zaustavi reprodukciju i servis ako se aktivnost zatvara ili ide u pozadinu
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
            if (activity != null && (activity.isFinishing() || activity.isDestroyed())) {
                try {
                    activity.stopService(new Intent(activity, MediaService.class));
                } catch (Exception ignored) {}
            }
        }
    }

    private void applyVideoMaskFromWebView(WebView webView) {
        if (dimView == null) return;
        if (webView == null) {
            dimView.clearVideoRect();
            return;
        }

        int[] wvLoc = new int[2];
        int[] dimLoc = new int[2];
        webView.getLocationInWindow(wvLoc);
        dimView.getLocationInWindow(dimLoc);

        float offsetX = wvLoc[0] - dimLoc[0];
        float offsetY = wvLoc[1] - dimLoc[1];

        webView.evaluateJavascript(JS_FIND_LARGEST_VIDEO, result -> {
            if (dimView == null || dimView.getVisibility() != View.VISIBLE) return;
            if (result == null || "null".equals(result)) {
                dimView.clearVideoRect();
                return;
            }
            try {
                String clean = result;
                if (clean.startsWith("\"") && clean.endsWith("\"")) {
                    clean = clean.substring(1, clean.length() - 1).replace("\\\"", "\"");
                }
                JSONObject obj = new JSONObject(clean);
                float left = (float) obj.optDouble("left", -1) + offsetX;
                float top = (float) obj.optDouble("top", -1) + offsetY;
                float right = (float) obj.optDouble("right", -1) + offsetX;
                float bottom = (float) obj.optDouble("bottom", -1) + offsetY;

                if (right > left && bottom > top) {
                    dimView.setVideoRect(left, top, right, bottom);
                } else {
                    dimView.clearVideoRect();
                }
            } catch (Exception ignored) {
                dimView.clearVideoRect();
            }
        });
    }
}
