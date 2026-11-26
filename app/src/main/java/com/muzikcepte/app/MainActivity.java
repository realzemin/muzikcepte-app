package com.muzikcepte.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    public static MainActivity instance;
    public BackgroundAudioWebView webView;
    private ProgressBar progressBar;
    private FrameLayout adViewContainer;

    // AdMob IDs
    public static final String ADMOB_BANNER_ID = "ca-app-pub-6369758923216272/1294823146";
    public static final String ADMOB_INTERSTITIAL_ID = "ca-app-pub-6369758923216272/2251400339";
    public static final String ADMOB_REWARDED_ID = "ca-app-pub-6369758923216272/9604558008";

    // Google Play Billing Product ID
    public static final String PRODUCT_ID_LIFETIME = "muzikcepte_vip_lifetime";
    public static final String PRODUCT_ID_LIFETIME_LEGACY = "muzikcepte_vip_reklamsiz";
    public static final String PRODUCT_ID_MONTHLY = "muzikcepte_vip_monthly";
    public static final String PRODUCT_ID_WEEKLY = "muzikcepte_vip_weekly";
    public static final String PRODUCT_ID_VIP = PRODUCT_ID_LIFETIME;

    private AdView adView;
    private InterstitialAd mInterstitialAd;
    private RewardedAd mRewardedAd;
    private BillingClient billingClient;
    private ProductDetails vipProductDetails;
    private final java.util.Map<String, ProductDetails> productDetailsMap = new java.util.concurrent.ConcurrentHashMap<>();

    private int trackChangeCounter = 0;
    private boolean isFirstInteractionDone = false;
    private long lastInterstitialShowTime = System.currentTimeMillis();
    private boolean isVip = false;
    private long rewardedAdFreeUntil = 0;
    private SharedPreferences prefs;

    @Override
    protected void onPause() {
        super.onPause();
        if (adView != null) adView.pause();
        if (webView != null) webView.resumeTimers();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (webView != null) webView.resumeTimers();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adView != null) adView.resume();
        checkRewardedExpiration();
    }

    @Override
    protected void onDestroy() {
        if (adView != null) adView.destroy();
        if (billingClient != null) {
            try { billingClient.endConnection(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @SuppressLint({"SetJavaScriptEnabled", "SourceLockedOrientationActivity"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        prefs = getSharedPreferences("MuzikCeptePrefs", MODE_PRIVATE);
        isVip = prefs.getBoolean("is_vip", false);
        rewardedAdFreeUntil = prefs.getLong("rewarded_until", 0);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.parseColor("#0B0F19"));
        getWindow().setNavigationBarColor(Color.parseColor("#0B0F19"));

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        adViewContainer = findViewById(R.id.adViewContainer);

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

        // Request Notification permission on Android 13+ (API 33+) for lockscreen / notification controls
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }

        // 1. Initialize Google AdMob
        MobileAds.initialize(this, initializationStatus -> {});

        // 2. Setup Ads & Billing
        setupBillingClient();
        if (!isVip && !isRewardedActive()) {
            loadBannerAd();
            loadInterstitialAd();
        }
        loadRewardedAd();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                webView.evaluateJavascript(
                    "if(document.getElementById('fullPlayer') && document.getElementById('fullPlayer').classList.contains('active')){ closeFullPlayer(); } else if(window.canGoBackApp && window.canGoBackApp()){ window.goBackApp(); } else { window.AndroidBridge.closeApp(); }",
                    null
                );
            }
        });

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    // ==========================================
    // Media Playback Notification Callbacks
    // ==========================================
        public void stopAudioEngine() {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript("if(window.stopAudio) window.stopAudio();", null);
            }
        });
    }

    public void togglePlayPause() {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript("if(window.togglePlay) window.togglePlay();", null);
            }
        });
    }

    public void playNextTrack() {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript("if(window.nextTrack) window.nextTrack();", null);
            }
        });
    }

    public void playPrevTrack() {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript("if(window.prevTrack) window.prevTrack();", null);
            }
        });
    }

    public void seekToPosition(long posMs) {
        runOnUiThread(() -> {
            if (webView != null) {
                double sec = posMs / 1000.0;
                webView.evaluateJavascript("if(window.seekToSeconds) window.seekToSeconds(" + sec + ");", null);
            }
        });
    }

    public void updateJsRadioState(boolean isPlaying) {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript("if(window.onNativeRadioState) window.onNativeRadioState(" + isPlaying + ");", null);
            }
        });
    }

    // ==========================================
    // AdMob Banner, Interstitial, Rewarded
    // ==========================================
    private boolean isRewardedActive() {
        return System.currentTimeMillis() < rewardedAdFreeUntil;
    }

    private void checkRewardedExpiration() {
        if (!isVip && isRewardedActive()) {
            if (adViewContainer != null) adViewContainer.setVisibility(View.GONE);
        } else if (!isVip && !isRewardedActive()) {
            if (adView == null) {
                loadBannerAd();
            } else if (adViewContainer != null && adViewContainer.getVisibility() != View.VISIBLE) {
                adViewContainer.setVisibility(View.VISIBLE);
            }
        }
    }

    private void loadBannerAd() {
        if (isVip || isRewardedActive()) return;
        runOnUiThread(() -> {
            try {
                if (adViewContainer == null) return;
                adView = new AdView(this);
                adView.setAdUnitId(ADMOB_BANNER_ID);
                adView.setAdSize(AdSize.BANNER);
                adViewContainer.removeAllViews();
                adViewContainer.addView(adView);

                AdRequest adRequest = new AdRequest.Builder().build();
                adView.setAdListener(new com.google.android.gms.ads.AdListener() {
                    @Override
                    public void onAdLoaded() {
                        super.onAdLoaded();
                        if (!isVip && !isRewardedActive()) {
                            adViewContainer.setVisibility(View.VISIBLE);
                        }
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        super.onAdFailedToLoad(loadAdError);
                    }
                });
                adView.loadAd(adRequest);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void loadInterstitialAd() {
        if (isVip || isRewardedActive()) return;
        AdRequest adRequest = new AdRequest.Builder().build();
        InterstitialAd.load(this, ADMOB_INTERSTITIAL_ID, adRequest,
            new InterstitialAdLoadCallback() {
                @Override
                public void onAdLoaded(@NonNull InterstitialAd interstitialAd) {
                    mInterstitialAd = interstitialAd;
                    mInterstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                        @Override
                        public void onAdDismissedFullScreenContent() {
                            mInterstitialAd = null;
                            loadInterstitialAd();
                            runOnUiThread(() -> {
                                if (webView != null) {
                                    webView.evaluateJavascript("if(window.showAdFreeNudge) window.showAdFreeNudge();", null);
                                }
                            });
                        }

                        @Override
                        public void onAdFailedToShowFullScreenContent(@NonNull com.google.android.gms.ads.AdError adError) {
                            mInterstitialAd = null;
                            loadInterstitialAd();
                        }
                    });
                }

                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                    mInterstitialAd = null;
                }
            });
    }

    private void handleFirstClickAd() {
        if (isVip || isRewardedActive()) return;
        if (!isFirstInteractionDone) {
            isFirstInteractionDone = true;
            runOnUiThread(() -> {
                if (mInterstitialAd != null) {
                    mInterstitialAd.show(MainActivity.this);
                } else {
                    loadInterstitialAd();
                    if (webView != null) {
                        webView.evaluateJavascript("if(window.showAdFreeNudge) window.showAdFreeNudge();", null);
                    }
                }
            });
        }
    }

    private void triggerTrackChangeAd() {
        if (isVip || isRewardedActive()) return;
        if (!isFirstInteractionDone) {
            handleFirstClickAd();
            return;
        }
        trackChangeCounter++;
        // Tatlı bir rahatsızlık: İlk tıklamadan sonra her 3 şarkıda bir geçiş reklamı
        if (trackChangeCounter >= 3) {
            trackChangeCounter = 0;
            runOnUiThread(() -> {
                if (mInterstitialAd != null) {
                    mInterstitialAd.show(MainActivity.this);
                } else {
                    loadInterstitialAd();
                }
            });
        }
    }

    private void loadRewardedAd() {
        AdRequest adRequest = new AdRequest.Builder().build();
        RewardedAd.load(this, ADMOB_REWARDED_ID, adRequest,
            new RewardedAdLoadCallback() {
                @Override
                public void onAdLoaded(@NonNull RewardedAd rewardedAd) {
                    mRewardedAd = rewardedAd;
                    mRewardedAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                        @Override
                        public void onAdDismissedFullScreenContent() {
                            mRewardedAd = null;
                            loadRewardedAd();
                        }
                    });
                }

                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                    mRewardedAd = null;
                }
            });
    }

    public void showRewardedAd() {
        runOnUiThread(() -> {
            if (mRewardedAd != null) {
                mRewardedAd.show(this, rewardItem -> {
                    // 30 Minutes ad-free reward unlocked!
                    rewardedAdFreeUntil = System.currentTimeMillis() + (30 * 60 * 1000L);
                    prefs.edit().putLong("rewarded_until", rewardedAdFreeUntil).apply();

                    if (adViewContainer != null) adViewContainer.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "🎉 Harika! 30 Dakika Boyunca Sıfır Reklamla Kesintisiz Müzik Başladı!", Toast.LENGTH_LONG).show();

                    webView.evaluateJavascript("if(window.onRewardedUnlocked) window.onRewardedUnlocked(1800);", null);
                });
            } else {
                Toast.makeText(MainActivity.this, "Ödüllü video hazırlanıyor, lütfen 5 saniye sonra tekrar deneyin.", Toast.LENGTH_SHORT).show();
                loadRewardedAd();
            }
        });
    }

    // ==========================================
    // Google Play Billing (In-App Purchases / VIP)
    // ==========================================
    private final PurchasesUpdatedListener purchasesUpdatedListener = (billingResult, purchases) -> {
        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                handlePurchase(purchase);
            }
        }
    };

    private void setupBillingClient() {
        PendingPurchasesParams pendingPurchasesParams = PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build();
        billingClient = BillingClient.newBuilder(this)
                .setListener(purchasesUpdatedListener)
                .enablePendingPurchases(pendingPurchasesParams)
                .build();

        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    queryAllVipProducts();
                    restorePreviousPurchases();
                }
            }

            @Override
            public void onBillingServiceDisconnected() {}
        });
    }

    private void queryAllVipProducts() {
        // 1. In-App Products (Lifetime)
        List<QueryProductDetailsParams.Product> inAppList = new ArrayList<>();
        inAppList.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID_LIFETIME)
                .setProductType(BillingClient.ProductType.INAPP)
                .build());
        inAppList.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID_LIFETIME_LEGACY)
                .setProductType(BillingClient.ProductType.INAPP)
                .build());

        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(inAppList).build(),
            (billingResult, productDetailsResult) -> {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK
                        && productDetailsResult != null
                        && productDetailsResult.getProductDetailsList() != null) {
                    for (ProductDetails pd : productDetailsResult.getProductDetailsList()) {
                        productDetailsMap.put(pd.getProductId(), pd);
                        vipProductDetails = pd;
                    }
                }
            }
        );

        // 2. Subscriptions (Monthly & Weekly)
        List<QueryProductDetailsParams.Product> subsList = new ArrayList<>();
        subsList.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID_MONTHLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build());
        subsList.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID_WEEKLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build());

        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(subsList).build(),
            (billingResult, productDetailsResult) -> {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK
                        && productDetailsResult != null
                        && productDetailsResult.getProductDetailsList() != null) {
                    for (ProductDetails pd : productDetailsResult.getProductDetailsList()) {
                        productDetailsMap.put(pd.getProductId(), pd);
                    }
                }
            }
        );
    }

    private void restorePreviousPurchases() {
        // Restore In-App purchases
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
            (billingResult, purchases) -> {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    for (Purchase purchase : purchases) {
                        for (String prodId : purchase.getProducts()) {
                            if ((prodId.equals(PRODUCT_ID_LIFETIME) || prodId.equals(PRODUCT_ID_LIFETIME_LEGACY))
                                    && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                                activateVipStatus();
                                return;
                            }
                        }
                    }
                }
            }
        );

        // Restore Subscriptions
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
            (billingResult, purchases) -> {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    for (Purchase purchase : purchases) {
                        for (String prodId : purchase.getProducts()) {
                            if ((prodId.equals(PRODUCT_ID_MONTHLY) || prodId.equals(PRODUCT_ID_WEEKLY))
                                    && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                                activateVipStatus();
                                return;
                            }
                        }
                    }
                }
            }
        );
    }

    private void handlePurchase(Purchase purchase) {
        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged()) {
                AcknowledgePurchaseParams acknowledgePurchaseParams =
                        AcknowledgePurchaseParams.newBuilder()
                                .setPurchaseToken(purchase.getPurchaseToken())
                                .build();
                billingClient.acknowledgePurchase(acknowledgePurchaseParams, billingResult -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        activateVipStatus();
                    }
                });
            } else {
                activateVipStatus();
            }
        }
    }

    private void activateVipStatus() {
        isVip = true;
        prefs.edit().putBoolean("is_vip", true).apply();
        runOnUiThread(() -> {
            if (adViewContainer != null) adViewContainer.setVisibility(View.GONE);
            Toast.makeText(MainActivity.this, "👑 Tebrikler! VIP Üyeliğiniz Aktif Edildi. Tüm Reklamlar Kaldırıldı!", Toast.LENGTH_LONG).show();
            webView.evaluateJavascript("if(window.onVipActivated) window.onVipActivated();", null);
        });
    }

    public void launchVipPurchase(String plan) {
        runOnUiThread(() -> {
            if (isVip) {
                Toast.makeText(MainActivity.this, "Zaten VIP üyesiniz, tüm reklamlar kapalı!", Toast.LENGTH_SHORT).show();
                return;
            }

            String targetProductId;
            if ("monthly".equalsIgnoreCase(plan)) {
                targetProductId = PRODUCT_ID_MONTHLY;
            } else if ("weekly".equalsIgnoreCase(plan)) {
                targetProductId = PRODUCT_ID_WEEKLY;
            } else {
                targetProductId = PRODUCT_ID_LIFETIME;
            }

            ProductDetails targetProduct = productDetailsMap.get(targetProductId);
            if (targetProduct == null && "lifetime".equalsIgnoreCase(plan)) {
                targetProduct = productDetailsMap.get(PRODUCT_ID_LIFETIME_LEGACY);
            }

            if (targetProduct != null) {
                BillingFlowParams.ProductDetailsParams.Builder productDetailsParamsBuilder =
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(targetProduct);

                // If subscription, attach selected offer token
                if (targetProduct.getProductType().equals(BillingClient.ProductType.SUBS)) {
                    List<ProductDetails.SubscriptionOfferDetails> offers = targetProduct.getSubscriptionOfferDetails();
                    if (offers != null && !offers.isEmpty()) {
                        productDetailsParamsBuilder.setOfferToken(offers.get(0).getOfferToken());
                    }
                }

                BillingFlowParams billingFlowParams = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(Collections.singletonList(productDetailsParamsBuilder.build()))
                        .build();

                billingClient.launchBillingFlow(MainActivity.this, billingFlowParams);
            } else {
                Toast.makeText(MainActivity.this, "Google Play Mağaza bağlantısı kuruluyor, lütfen tekrar deneyin.", Toast.LENGTH_SHORT).show();
                queryAllVipProducts();
            }
        });
    }

    public void launchVipPurchase() {
        launchVipPurchase("lifetime");
    }

    // ==========================================
    // WebView & JavaScript Bridge Setup
    // ==========================================
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
        // Pure genuine Chrome Mobile User-Agent to completely eliminate Google/YouTube throttling
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");

        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.addJavascriptInterface(new Object() {

            @JavascriptInterface
            public void buyVipPlan(String plan) {
                MainActivity.this.launchVipPurchase(plan);
            }

            @JavascriptInterface
            public void onFirstClick() {
                handleFirstClickAd();
            }

            @JavascriptInterface
            public void onTrackChanged() {
                triggerTrackChangeAd();
            }

            @JavascriptInterface
            public void playNativeRadio(String url, String name, String genre, String logo) {
                runOnUiThread(() -> {
                    try {
                        Intent serviceIntent = new Intent(MainActivity.this, MusicPlaybackService.class);
                        serviceIntent.setAction(MusicPlaybackService.ACTION_PLAY_RADIO);
                        serviceIntent.putExtra("radio_url", url);
                        serviceIntent.putExtra(MusicPlaybackService.EXTRA_TITLE, name);
                        serviceIntent.putExtra(MusicPlaybackService.EXTRA_ARTIST, "🔴 Canlı Yayın • " + genre);
                        serviceIntent.putExtra(MusicPlaybackService.EXTRA_ART_URL, logo);
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
            public void toggleNativePlayback() {
                runOnUiThread(() -> {
                    try {
                        Intent serviceIntent = new Intent(MainActivity.this, MusicPlaybackService.class);
                        serviceIntent.setAction(MusicPlaybackService.ACTION_TOGGLE_PLAY);
                        startService(serviceIntent);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }

            @JavascriptInterface
            public void closeApp() {
                runOnUiThread(() -> finish());
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
                startBackgroundPlayback(title, artist, true, "", 0, 0);
            }

            @JavascriptInterface
            public void startBackgroundPlayback(String title, String artist, boolean isPlaying) {
                startBackgroundPlayback(title, artist, isPlaying, "", 0, 0);
            }

            @JavascriptInterface
            public void startBackgroundPlayback(String title, String artist, boolean isPlaying, String artUrl, int durationSec, int positionSec) {
                runOnUiThread(() -> {
                    try {
                        Intent serviceIntent = new Intent(MainActivity.this, MusicPlaybackService.class);
                        serviceIntent.setAction(MusicPlaybackService.ACTION_START);
                        serviceIntent.putExtra(MusicPlaybackService.EXTRA_TITLE, title);
                        serviceIntent.putExtra(MusicPlaybackService.EXTRA_ARTIST, artist);
                        serviceIntent.putExtra(MusicPlaybackService.EXTRA_IS_PLAYING, isPlaying);
                        serviceIntent.putExtra(MusicPlaybackService.EXTRA_ART_URL, artUrl);
                        serviceIntent.putExtra(MusicPlaybackService.EXTRA_DURATION, (long) durationSec * 1000L);
                        serviceIntent.putExtra(MusicPlaybackService.EXTRA_POSITION, (long) positionSec * 1000L);
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
            public void updatePlaybackState(String title, String artist, boolean isPlaying) {
                runOnUiThread(() -> {
                    try {
                        Intent serviceIntent = new Intent(MainActivity.this, MusicPlaybackService.class);
                        serviceIntent.setAction(MusicPlaybackService.ACTION_START);
                        serviceIntent.putExtra(MusicPlaybackService.EXTRA_TITLE, title);
                        serviceIntent.putExtra(MusicPlaybackService.EXTRA_ARTIST, artist);
                        serviceIntent.putExtra(MusicPlaybackService.EXTRA_IS_PLAYING, isPlaying);
                        startService(serviceIntent);
                    } catch (Exception ignored) {}
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

            @JavascriptInterface
            public void showRewardedAd() {
                MainActivity.this.showRewardedAd();
            }

            @JavascriptInterface
            public void launchVipPurchase() {
                MainActivity.this.launchVipPurchase();
            }

            @JavascriptInterface
            public boolean isVipUser() {
                return isVip;
            }

            @JavascriptInterface
            public long getRewardedRemainingSeconds() {
                long left = (rewardedAdFreeUntil - System.currentTimeMillis()) / 1000;
                return Math.max(0, left);
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
                            "⚡ Alternatif MP3 İndir (OnlyMP3)",
                            "💾 Çevrimdışı Kitaplığıma Kaydet"
                        };
                        builder.setItems(options, (dialog, which) -> {
                            try {
                                if (which == 0) {
                                    String url = "https://onlymp3.to/tr/?url=https://www.youtube.com/watch?v=" + ytId;
                                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                                } else if (which == 1) {
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
                        conn.setRequestProperty("User-Agent", "MuzikCepteApp/1.6.0 (https://muzikcepte.app)");
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
