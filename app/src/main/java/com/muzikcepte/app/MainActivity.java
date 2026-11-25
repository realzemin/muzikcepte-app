package com.muzikcepte.app;

import android.annotation.SuppressLint;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    public static MainActivity instance;
    public WebView webView;
    private ProgressBar progressBar;

    // Dedicated In-App Video & Audio Player
    private LinearLayout playerContainer;
    private WebView playerWebView;
    private TextView txtPlayerTitle;
    private TextView txtPlayerSub;
    private ProgressBar playerProgressBar;
    private String currentPlayingYtId = "";
    private String currentPlayingTitle = "";

    @SuppressLint({"SetJavaScriptEnabled", "SourceLockedOrientationActivity"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.parseColor("#0B0F19"));
        getWindow().setNavigationBarColor(Color.parseColor("#0B0F19"));

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);

        playerContainer = findViewById(R.id.playerContainer);
        playerWebView = findViewById(R.id.playerWebView);
        txtPlayerTitle = findViewById(R.id.txtPlayerTitle);
        txtPlayerSub = findViewById(R.id.txtPlayerSub);
        playerProgressBar = findViewById(R.id.playerProgressBar);

        findViewById(R.id.btnBackFromPlayer).setOnClickListener(v -> closePlayer());
        findViewById(R.id.btnDownloadInPlayer).setOnClickListener(v -> openDownloadOptions(currentPlayingYtId, currentPlayingTitle));
        findViewById(R.id.btnPipInPlayer).setOnClickListener(v -> enterPIP());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainRoot), (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() |
                WindowInsetsCompat.Type.displayCutout()
            );
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });

        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
            .setAppearanceLightStatusBars(false);

        setupWebView();
        setupPlayerWebView();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (playerContainer != null && playerContainer.getVisibility() == View.VISIBLE) {
                    closePlayer();
                    return;
                }
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    finish();
                }
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    public void enterPIP() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                PictureInPictureParams params = new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9))
                        .build();
                enterPictureInPictureMode(params);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (playerContainer != null && playerContainer.getVisibility() == View.VISIBLE) {
            findViewById(R.id.playerTopBar).setVisibility(isInPictureInPictureMode ? View.GONE : View.VISIBLE);
        }
    }

    public void openInAppPlayer(String ytId, String title, String artist) {
        currentPlayingYtId = ytId;
        currentPlayingTitle = title + " - " + artist;
        runOnUiThread(() -> {
            if (txtPlayerTitle != null) txtPlayerTitle.setText(title);
            if (txtPlayerSub != null) txtPlayerSub.setText(artist + " • Orijinal Klip ve Stüdyo Sesi");
            if (playerContainer != null) playerContainer.setVisibility(View.VISIBLE);
            if (playerWebView != null) {
                String targetUrl = "https://m.youtube.com/watch?v=" + ytId;
                playerWebView.loadUrl(targetUrl);
            }
        });
    }

    public void closePlayer() {
        runOnUiThread(() -> {
            if (playerWebView != null) {
                playerWebView.loadUrl("about:blank");
            }
            if (playerContainer != null) {
                playerContainer.setVisibility(View.GONE);
            }
        });
    }

    public void openDownloadOptions(String ytId, String title) {
        runOnUiThread(() -> {
            String safeTitle = (title == null || title.isEmpty()) ? "Müzik Parçası" : title;
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("⬇️ " + safeTitle);
            String[] options = {
                "🎵 MP3 İndir (320kbps HD Ses)",
                "🎬 MP4 Video İndir (HD Klip)",
                "💾 Çevrimdışı Kitaplığıma Kaydet (İnternetsiz Dinle)",
                "🔗 Şarkı Bağlantısını Paylaş"
            };
            builder.setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0:
                        // Direct high quality MP3 converter
                        String mp3Url = "https://y2mate.nu/tr/?url=https://www.youtube.com/watch?v=" + ytId;
                        Intent mp3Intent = new Intent(Intent.ACTION_VIEW, Uri.parse(mp3Url));
                        startActivity(mp3Intent);
                        break;
                    case 1:
                        // HD Video MP4 Downloader
                        String mp4Url = "https://snapsave.io/tr/?url=https://www.youtube.com/watch?v=" + ytId;
                        Intent mp4Intent = new Intent(Intent.ACTION_VIEW, Uri.parse(mp4Url));
                        startActivity(mp4Intent);
                        break;
                    case 2:
                        // Offline library save
                        if (webView != null) {
                            String escaped = safeTitle.replace("'", "\\'");
                            webView.evaluateJavascript("if(window.addTrackToOffline) window.addTrackToOffline('" + ytId + "', '" + escaped + "');", null);
                        }
                        Toast.makeText(this, "✅ '" + safeTitle + "' Çevrimdışı Kitaplığınıza kaydedildi!", Toast.LENGTH_LONG).show();
                        break;
                    case 3:
                        Intent sendIntent = new Intent();
                        sendIntent.setAction(Intent.ACTION_SEND);
                        sendIntent.putExtra(Intent.EXTRA_TEXT, "MüzikCepte ile dinle ve indir: " + safeTitle + " https://youtu.be/" + ytId);
                        sendIntent.setType("text/plain");
                        startActivity(Intent.createChooser(sendIntent, "Şarkıyı Paylaş"));
                        break;
                }
            });
            builder.setNegativeButton("Kapat", null);
            builder.show();
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupPlayerWebView() {
        WebSettings s = playerWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        playerWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (playerProgressBar != null) playerProgressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (playerProgressBar != null) playerProgressBar.setVisibility(View.GONE);
                // Clean header elements so it looks like an authentic integrated music player
                String cleanCss = "var style = document.createElement('style');" +
                        "style.innerHTML = 'header, ytm-header-bar, .mobile-topbar-header, ytm-pivot-bar-renderer { display: none !important; }';" +
                        "document.head.appendChild(style);";
                view.evaluateJavascript(cleanCss, null);
            }
        });

        playerWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (playerProgressBar != null) {
                    playerProgressBar.setProgress(newProgress);
                    if (newProgress >= 100) playerProgressBar.setVisibility(View.GONE);
                }
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(settings.getUserAgentString() + " MuzikCepteApp/1.0.3");

        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void triggerPip() {
                runOnUiThread(() -> enterPIP());
            }

            @JavascriptInterface
            public void playInApp(String ytId, String title, String artist) {
                openInAppPlayer(ytId, title, artist);
            }

            @JavascriptInterface
            public void openDownloadModal(String ytId, String title) {
                openDownloadOptions(ytId, title);
            }

            @JavascriptInterface
            public void shareMusic(String title, String url) {
                try {
                    Intent sendIntent = new Intent();
                    sendIntent.setAction(Intent.ACTION_SEND);
                    sendIntent.putExtra(Intent.EXTRA_TEXT, "MüzikCepte ile dinliyorum: " + title + " " + url);
                    sendIntent.setType("text/plain");
                    Intent shareIntent = Intent.createChooser(sendIntent, "Şarkıyı Paylaş");
                    shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(shareIntent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            private void fetchYouTubeQuery(String q, JSONArray results, Set<String> seenIds) {
                try {
                    String encoded = URLEncoder.encode(q, "UTF-8");
                    URL url = new URL("https://www.youtube.com/results?search_query=" + encoded);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                    conn.setConnectTimeout(7000);
                    conn.setReadTimeout(7000);

                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        sb.append(line);
                    }
                    in.close();

                    String html = sb.toString();
                    Pattern pattern = Pattern.compile("ytInitialData\\s*=\\s*(\\{.+?\\});</script>");
                    Matcher matcher = pattern.matcher(html);

                    if (matcher.find()) {
                        JSONObject root = new JSONObject(matcher.group(1));
                        JSONArray sections = root.getJSONObject("contents")
                                .getJSONObject("twoColumnSearchResultsRenderer")
                                .getJSONObject("primaryContents")
                                .getJSONObject("sectionListRenderer")
                                .getJSONArray("contents");

                        for (int s = 0; s < sections.length(); s++) {
                            JSONObject section = sections.getJSONObject(s);
                            if (section.has("itemSectionRenderer")) {
                                JSONArray contents = section.getJSONObject("itemSectionRenderer").getJSONArray("contents");
                                for (int i = 0; i < contents.length() && results.length() < 40; i++) {
                                    JSONObject item = contents.getJSONObject(i);
                                    if (item.has("videoRenderer")) {
                                        JSONObject vr = item.getJSONObject("videoRenderer");
                                        String vid = vr.optString("videoId");
                                        if (vid.isEmpty() || seenIds.contains(vid)) continue;
                                        seenIds.add(vid);

                                        String title = "";
                                        if (vr.has("title") && vr.getJSONObject("title").has("runs")) {
                                            title = vr.getJSONObject("title").getJSONArray("runs").getJSONObject(0).optString("text");
                                        }
                                        String author = "";
                                        if (vr.has("ownerText") && vr.getJSONObject("ownerText").has("runs")) {
                                            author = vr.getJSONObject("ownerText").getJSONArray("runs").getJSONObject(0).optString("text");
                                        }
                                        String duration = vr.has("lengthText") ? vr.getJSONObject("lengthText").optString("simpleText", "03:30") : "03:30";
                                        String thumb = "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg";

                                        JSONObject row = new JSONObject();
                                        row.put("id", vid);
                                        row.put("title", title);
                                        row.put("artist", author);
                                        row.put("duration", duration);
                                        row.put("art", thumb);
                                        row.put("ytId", vid);
                                        results.put(row);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @JavascriptInterface
            public void searchOnlineMusic(String query) {
                new Thread(() -> {
                    try {
                        JSONArray results = new JSONArray();
                        Set<String> seenIds = new HashSet<>();

                        fetchYouTubeQuery(query, results, seenIds);
                        if (results.length() < 25) {
                            fetchYouTubeQuery(query + " en çok dinlenen", results, seenIds);
                        }

                        runOnUiThread(() -> {
                            webView.evaluateJavascript("if(window.onMusicSearchResults) window.onMusicSearchResults(" + results.toString() + ");", null);
                        });

                    } catch (Exception e) {
                        e.printStackTrace();
                        runOnUiThread(() -> {
                            webView.evaluateJavascript("if(window.onMusicSearchError) window.onMusicSearchError('" + e.getMessage() + "');", null);
                        });
                    }
                }).start();
            }

            @JavascriptInterface
            public void fetchLyrics(String artist, String title) {
                new Thread(() -> {
                    try {
                        String cleanArtist = URLEncoder.encode(artist, "UTF-8");
                        String cleanTitle = URLEncoder.encode(title, "UTF-8");
                        URL url = new URL("https://lrclib.net/api/get?artist_name=" + cleanArtist + "&track_name=" + cleanTitle);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setRequestProperty("User-Agent", "MuzikCepteApp/1.0.3 (https://muzikcepte.app)");
                        conn.setConnectTimeout(6000);
                        conn.setReadTimeout(6000);

                        if (conn.getResponseCode() == 200) {
                            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = in.readLine()) != null) {
                                sb.append(line);
                            }
                            in.close();
                            String jsonStr = sb.toString();
                            runOnUiThread(() -> {
                                webView.evaluateJavascript("if(window.onLyricsLoaded) window.onLyricsLoaded(" + jsonStr + ");", null);
                            });
                        } else {
                            runOnUiThread(() -> {
                                webView.evaluateJavascript("if(window.onLyricsNotFound) window.onLyricsNotFound();", null);
                            });
                        }
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            webView.evaluateJavascript("if(window.onLyricsNotFound) window.onLyricsNotFound();", null);
                        });
                    }
                }).start();
            }

        }, "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("file:///android_asset/") || url.startsWith("file://") || url.startsWith("about:blank") || url.startsWith("javascript:")) {
                    return false;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (progressBar != null) progressBar.setVisibility(View.GONE);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar != null) {
                    progressBar.setProgress(newProgress);
                    if (newProgress >= 100) progressBar.setVisibility(View.GONE);
                }
            }
        });
    }
}
