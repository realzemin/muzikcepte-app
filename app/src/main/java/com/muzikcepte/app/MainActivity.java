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
import android.widget.ProgressBar;

import androidx.activity.OnBackPressedCallback;
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

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
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
        if (webView != null) {
            webView.evaluateJavascript("if(window.onPipChanged) window.onPipChanged(" + isInPictureInPictureMode + ");", null);
        }
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
        settings.setUserAgentString(settings.getUserAgentString() + " MuzikCepteApp/1.0.2");

        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void triggerPip() {
                runOnUiThread(() -> enterPIP());
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
                        conn.setRequestProperty("User-Agent", "MuzikCepteApp/1.0.1 (https://muzikcepte.app)");
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
                if (url.contains("youtube.com") || url.contains("youtu.be")) {
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
