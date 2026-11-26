package com.muzikcepte.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.webkit.WebView;

public class BackgroundAudioWebView extends WebView {

    public BackgroundAudioWebView(Context context) {
        super(context);
    }

    public BackgroundAudioWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public BackgroundAudioWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        // Intercept GONE/INVISIBLE when screen turns off or app is minimized,
        // and force Chromium to see VISIBLE so background audio playback NEVER stops!
        super.onWindowVisibilityChanged(View.VISIBLE);
    }

    @Override
    public void onPause() {
        // Prevent default pause that halts media threads
    }
}
