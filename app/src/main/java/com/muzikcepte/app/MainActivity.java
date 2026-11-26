package com.muzikcepte.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;

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
    public BackgroundAudioWebView webView;
    private ProgressBar progressBar;

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.resumeTimers();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (webView != null) {
            webView.resumeTimers();
        }
    }

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
                // If full player is active in JS, close it first
                webView.evaluateJavascript("if(document.getElementById('fullPlayer') && document.getElementById('fullPlayer').classList.contains('active')){ closeFullPlayer(); } else if(window.canGoBackApp && window.canGoBackApp()){ window.goBackApp(); } else { window.AndroidBridge.closeApp(); }", null);
            }
        });

        // Load via secure HTTPS domain so YouTube IFrame API has valid origin and never shows errors!
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
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
        settings.setUserAgentString(settings.getUserAgentString() + " MuzikCepteApp/1.2.0");

        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.addJavascriptInterface(new Object() {

            @JavascriptInterface
            public void closeApp() {
                runOnUiThread(() -> {
                    finish();
                });
            }

            @JavascriptInterface
            public void requestAudioFocus() {
                try {
                    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                    }
                } catch (Exception ignored) {}
            }

            @JavascriptInterface
            public void startBackgroundPlayback(String title, String artist) {
                runOnUiThread(() -> {
                    try {
                        Intent serviceIntent = new Intent(MainActivity.this, MusicPlaybackService.class);
                        serviceIntent.setAction(MusicPlaybackService.ACTION_START);
                        serviceIntent.putExtra(MusicPlaybackService.EXTRA_TITLE, title);
                        serviceIntent.putExtra(MusicPlaybackService.EXTRA_ARTIST, artist);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent);
                        } else {
                            startService(serviceIntent);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }

            @JavascriptInterface
            public void stopBackgroundPlayback() {
                runOnUiThread(() -> {
                    try {
                        Intent serviceIntent = new Intent(MainActivity.this, MusicPlaybackService.class);
                        serviceIntent.setAction(MusicPlaybackService.ACTION_STOP);
                        startService(serviceIntent);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
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
                                        String duration = vr.has("lengthText") ? vr.getJSONObject("lengthText").optString("simpleText", "03:45") : "03:45";
                                        
                                        // Filter out movies / long mixes over 15 minutes
                                        if (duration.split(":").length > 2) continue;
                                        String[] parts = duration.split(":");
                                        if (parts.length == 2) {
                                            try {
                                                int m = Integer.parseInt(parts[0].trim());
                                                if (m > 15) continue;
                                            } catch (Exception ignored) {}
                                        }

                                        String thumb = "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg";

                                        JSONObject row = new JSONObject();
                                        row.put("id", vid);
                                        row.put("ytId", vid);
                                        row.put("title", title);
                                        row.put("artist", author);
                                        row.put("duration", duration);
                                        row.put("art", thumb);
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
            public void searchOnlineMusic(String query, int page) {
                new Thread(() -> {
                    try {
                        JSONArray results = new JSONArray();
                        Set<String> seenIds = new HashSet<>();

                        if (page <= 1) {
                            fetchYouTubeQuery(query, results, seenIds);
                            if (results.length() < 25) {
                                fetchYouTubeQuery(query + " şarkıları", results, seenIds);
                            }
                        } else if (page == 2) {
                            fetchYouTubeQuery(query + " albüm", results, seenIds);
                            fetchYouTubeQuery(query + " hit", results, seenIds);
                        } else {
                            fetchYouTubeQuery(query + " official audio", results, seenIds);
                            fetchYouTubeQuery(query + " klip", results, seenIds);
                        }

                        runOnUiThread(() -> {
                            webView.evaluateJavascript("if(window.onMusicSearchResults) window.onMusicSearchResults(" + results.toString() + ", " + page + ");", null);
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
            public void searchOnlineMusic(String query) {
                searchOnlineMusic(query, 1);
            }

            @JavascriptInterface
            public void downloadMusicFile(String ytId, String title) {
                downloadMusicFile(ytId, title, "");
            }

            @JavascriptInterface
            public void downloadMusicFile(String ytId, String title, String artist) {
                runOnUiThread(() -> {
                    try {
                        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this);
                        builder.setTitle("⬇️ " + title);
                        String[] options = {
                            "🎵 1. Hızlı MP3 İndir (Snapsave - Önerilen)",
                            "⚡ 2. Alternatif MP3 İndir (OnlyMP3)",
                            "🎬 3. HD Video İndir (MP4)",
                            "💾 4. Çevrimdışı Kitaplığıma Kaydet"
                        };
                        builder.setItems(options, (dialog, which) -> {
                            try {
                                if (which == 0 || which == 2) {
                                    String url = "https://snapsave.io/tr/?url=https://www.youtube.com/watch?v=" + ytId;
                                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                                } else if (which == 1) {
                                    String url = "https://onlymp3.to/tr/?url=https://www.youtube.com/watch?v=" + ytId;
                                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                                } else if (which == 3) {
                                    webView.evaluateJavascript("if(window.saveOfflineTrackById) window.saveOfflineTrackById('" + ytId + "');", null);
                                    Toast.makeText(MainActivity.this, "✅ '" + title + "' Çevrimdışı Listenize Eklendi!", Toast.LENGTH_SHORT).show();
                                }
                            } catch (Exception e) {
                                Toast.makeText(MainActivity.this, "Tarayıcı açılamadı.", Toast.LENGTH_SHORT).show();
                            }
                        });
                        builder.setNegativeButton("Kapat", null);
                        builder.show();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }

            @JavascriptInterface
            public void shareMusic(String title, String artist) {
                try {
                    Intent sendIntent = new Intent();
                    sendIntent.setAction(Intent.ACTION_SEND);
                    sendIntent.putExtra(Intent.EXTRA_TEXT, "MüzikCepte ile dinliyorum: " + title + " - " + artist);
                    sendIntent.setType("text/plain");
                    Intent shareIntent = Intent.createChooser(sendIntent, "Şarkıyı Paylaş");
                    shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(shareIntent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @JavascriptInterface
            public void fetchLyrics(String artist, String title) {
                new Thread(() -> {
                    try {
                        String cleanArtist = URLEncoder.encode(artist, "UTF-8");
                        String cleanTitle = URLEncoder.encode(title.replaceAll("\\(.*\\)", "").replaceAll("\\[.*\\]", "").trim(), "UTF-8");
                        URL url = new URL("https://lrclib.net/api/get?artist_name=" + cleanArtist + "&track_name=" + cleanTitle);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setRequestProperty("User-Agent", "MuzikCepteApp/1.2.0 (https://muzikcepte.app)");
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(5000);

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
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("https://appassets.androidplatform.net/") || url.startsWith("about:blank") || url.startsWith("javascript:")) {
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
