package com.golgekuklaci.game;

import android.graphics.Color;
import android.os.Bundle;
import android.webkit.WebView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;
import java.util.Locale;

/**
 * Edge-to-edge: the stage draws behind the status/navigation bars, display cutouts and the keyboard. The system insets are
 * passed to the page as CSS variables (--native-sat/sab/sal/sar, in CSS px) because Android WebView does not
 * reliably report env(safe-area-inset-*). They are re-sent after every page load.
 */
public class MainActivity extends BridgeActivity {
    private String insetsJs = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        // Dark stage: light status/navigation bar icons.
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(false);
        bars.setAppearanceLightNavigationBars(false);

        WebView webView = getBridge().getWebView();
        ViewCompat.setOnApplyWindowInsetsListener(webView, (view, insets) -> {
            // The keyboard (IME) counts as a bottom inset, so focused fields stay visible above it.
            Insets i = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            float d = getResources().getDisplayMetrics().density;
            insetsJs = String.format(Locale.US,
                "(function(s){s.setProperty('--native-sat','%.1fpx');s.setProperty('--native-sab','%.1fpx');"
                    + "s.setProperty('--native-sal','%.1fpx');s.setProperty('--native-sar','%.1fpx');})(document.documentElement.style)",
                i.top / d, i.bottom / d, i.left / d, i.right / d);
            sendInsets();
            return WindowInsetsCompat.CONSUMED;
        });
        getBridge().addWebViewListener(new WebViewListener() {
            @Override
            public void onPageLoaded(WebView view) { sendInsets(); }
        });
    }

    private void sendInsets() {
        if (insetsJs == null) return;
        WebView webView = getBridge().getWebView();
        webView.post(() -> webView.evaluateJavascript(insetsJs, null));
    }
}
