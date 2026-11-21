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
              "    if (window.__pipApplied) return;" +
              "    var foundEl = null;" +
              "    var bestArea = 0;" +
              "    function scanDoc(doc, hostEl) {" +
              "      try {" +
              "        var vids = doc.querySelectorAll('video');" +
              "        for (var i = 0; i < vids.length; i++) {" +
              "          var r = vids[i].getBoundingClientRect();" +
              "          var area = r.width * r.height;" +
              "          if (area > bestArea && r.width > 50 && r.height > 50) {" +
              "            bestArea = area;" +
              "            foundEl = hostEl || vids[i];" +
              "          }" +
              "        }" +
              "        var iframes = doc.querySelectorAll('iframe');" +
              "        for (var j = 0; j < iframes.length; j++) {" +
              "          var ir = iframes[j].getBoundingClientRect();" +
              "          var iArea = ir.width * ir.height;" +
              "          if (iArea > bestArea && ir.width > 120 && ir.height > 80) {" +
              "            bestArea = iArea;" +
              "            foundEl = hostEl || iframes[j];" +
              "          }" +
              "          try {" +
              "            if (iframes[j].contentDocument) {" +
              "              scanDoc(iframes[j].contentDocument, iframes[j]);" +
              "            }" +
              "          } catch(e2) {}" +
              "        }" +
              "      } catch(e1) {}" +
              "    }" +
              "    scanDoc(document, null);" +
              "    if (!foundEl) return;" +
              "    window.__pipApplied = true;" +
              "    window.__pipHidden = [];" +
              "    window.__pipSavedStyles = [];" +
              "    function saveStyle(el) {" +
              "      window.__pipSavedStyles.push({ el: el, style: el.getAttribute('style') });" +
              "    }" +
              "    var curr = foundEl;" +
              "    while (curr && curr !== document.body && curr !== document.documentElement) {" +
              "      var parent = curr.parentElement;" +
              "      if (parent) {" +
              "        for (var k = 0; k < parent.children.length; k++) {" +
              "          var sib = parent.children[k];" +
              "          if (sib !== curr && sib.tagName !== 'SCRIPT' && sib.tagName !== 'STYLE') {" +
              "            saveStyle(sib);" +
              "            sib.style.setProperty('display', 'none', 'important');" +
              "            window.__pipHidden.push(sib);" +
              "          }" +
              "        }" +
              "      }" +
              "      saveStyle(curr);" +
              "      curr.style.setProperty('position', 'static', 'important');" +
              "      curr.style.setProperty('width', '100vw', 'important');" +
              "      curr.style.setProperty('height', '100vh', 'important');" +
              "      curr.style.setProperty('max-width', '100vw', 'important');" +
              "      curr.style.setProperty('max-height', '100vh', 'important');" +
              "      curr.style.setProperty('margin', '0', 'important');" +
              "      curr.style.setProperty('padding', '0', 'important');" +
              "      curr.style.setProperty('transform', 'none', 'important');" +
              "      curr = parent;" +
              "    }" +
              "    saveStyle(document.documentElement);" +
              "    saveStyle(document.body);" +
              "    document.documentElement.style.setProperty('overflow', 'hidden', 'important');" +
              "    document.documentElement.style.setProperty('background', '#000', 'important');" +
              "    document.body.style.setProperty('overflow', 'hidden', 'important');" +
              "    document.body.style.setProperty('background', '#000', 'important');" +
              "    document.body.style.setProperty('margin', '0', 'important');" +
              "    document.body.style.setProperty('padding', '0', 'important');" +
              "  } catch(e) {}" +
              "})();"
            : "(function() {" +
              "  try {" +
              "    if (!window.__pipApplied) return;" +
              "    if (window.__pipSavedStyles) {" +
              "      for (var i = 0; i < window.__pipSavedStyles.length; i++) {" +
              "        var item = window.__pipSavedStyles[i];" +
              "        if (item && item.el) {" +
              "          if (item.style !== null) item.el.setAttribute('style', item.style);" +
              "          else item.el.removeAttribute('style');" +
              "        }" +
              "      }" +
              "    }" +
              "    window.__pipApplied = false;" +
              "    window.__pipHidden = null;" +
              "    window.__pipSavedStyles = null;" +
              "  } catch(e) {}" +
              "})();";

        webView.evaluateJavascript(js, null);
    }
}
