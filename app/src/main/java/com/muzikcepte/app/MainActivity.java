package com.muzikcepte.app;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
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

import android.content.ContentValues;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    public void updatePlaybackProgress(long currentSec, long durationSec) {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript("if(window.onNativeProgress) window.onNativeProgress(" + currentSec + ", " + durationSec + ");", null);
            }
        });
    }

    public void updateJsTrackState(boolean isPlaying, long durationMs) {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript("if(window.onNativeTrackState) window.onNativeTrackState(" + isPlaying + ", " + durationMs + ");", null);
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

        private String pendingDlYtId = "";
    private String pendingDlTitle = "";
    private String pendingDlArtist = "";

    public void requestDownloadWithReward(String ytId, String title, String artist) {
        if (isVip) {
            executeDirectDownload(ytId, title, artist);
            return;
        }

        pendingDlYtId = ytId;
        pendingDlTitle = title;
        pendingDlArtist = artist;

        runOnUiThread(() -> {
            if (mRewardedAd != null) {
                mRewardedAd.show(MainActivity.this, rewardItem -> {
                    Toast.makeText(MainActivity.this, "🎉 Video tamamlandı! Şarkı telefonunuza indiriliyor...", Toast.LENGTH_SHORT).show();
                    executeDirectDownload(pendingDlYtId, pendingDlTitle, pendingDlArtist);
                    if (webView != null) {
                        webView.evaluateJavascript("if(window.onDownloadUnlocked) window.onDownloadUnlocked('" + pendingDlYtId + "');", null);
                    }
                });
            } else {
                Toast.makeText(MainActivity.this, "📥 İndirme başlatılıyor...", Toast.LENGTH_SHORT).show();
                executeDirectDownload(ytId, title, artist);
                loadRewardedAd();
            }
        });
    }

        public void executeDirectDownload(String ytId, String title, String artist) {
        runOnUiThread(() -> {
            try {
                String songTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : "Müzik";
                String converterUrl = "https://onlymp3.to/tr/?url=https://www.youtube.com/watch?v=" + ytId;
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(converterUrl));
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(browserIntent);
                Toast.makeText(MainActivity.this, "📥 '" + songTitle + "' MP3 dönüştürücü açıldı. Tek dokunuşla indirebilirsiniz!", Toast.LENGTH_LONG).show();
                if (webView != null) {
                    webView.evaluateJavascript("if(window.onDownloadStarted) window.onDownloadStarted('" + ytId + "');", null);
                }
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "✅ Şarkı Çevrimdışı Kütüphanenize eklendi!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void showRewardedAd() {
        runOnUiThread(() -> {
            if (mRewardedAd != null) {
                mRewardedAd.show(this, rewardItem -> {
                    // 30 Minutes ad-free reward unlocked!
                    rewardedAdFreeUntil = System.currentTimeMillis() + (15 * 60 * 1000L);
                    prefs.edit().putLong("rewarded_until", rewardedAdFreeUntil).apply();

                    if (adViewContainer != null) adViewContainer.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "🎉 Harika! 15 Dakika Boyunca Sıfır Reklamla Kesintisiz Müzik Başladı!", Toast.LENGTH_LONG).show();

                    webView.evaluateJavascript("if(window.onRewardedUnlocked) window.onRewardedUnlocked(900);", null);
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
            public void playTrackNative(String ytId, String title, String artist, String artUrl) {
                executor.execute(() -> {
                    try {
                        String streamUrl = resolveDirectAudioStream(ytId);
                        if (streamUrl != null && !streamUrl.isEmpty()) {
                            runOnUiThread(() -> {
                                try {
                                    Intent serviceIntent = new Intent(MainActivity.this, MusicPlaybackService.class);
                                    serviceIntent.setAction(MusicPlaybackService.ACTION_PLAY_TRACK);
                                    serviceIntent.putExtra("stream_url", streamUrl);
                                    serviceIntent.putExtra(MusicPlaybackService.EXTRA_TITLE, title);
                                    serviceIntent.putExtra(MusicPlaybackService.EXTRA_ARTIST, artist);
                                    serviceIntent.putExtra(MusicPlaybackService.EXTRA_ART_URL, artUrl);
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        startForegroundService(serviceIntent);
                                    } else {
                                        startService(serviceIntent);
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            });
                        } else {
                            runOnUiThread(() -> {
                                Toast.makeText(MainActivity.this, "Ses akışı alınamadı, parça atlanıyor...", Toast.LENGTH_SHORT).show();
                                playNextTrack();
                            });
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }

            @JavascriptInterface
            public void seekTo(long seconds) {
                runOnUiThread(() -> {
                    try {
                        Intent serviceIntent = new Intent(MainActivity.this, MusicPlaybackService.class);
                        serviceIntent.setAction(MusicPlaybackService.ACTION_SEEK_TO);
                        serviceIntent.putExtra(MusicPlaybackService.EXTRA_POSITION, seconds * 1000L);
                        startService(serviceIntent);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }

            @JavascriptInterface
            public void skipSeconds(long seconds) {
                runOnUiThread(() -> {
                    try {
                        Intent serviceIntent = new Intent(MainActivity.this, MusicPlaybackService.class);
                        serviceIntent.setAction(MusicPlaybackService.ACTION_SKIP_SECONDS);
                        serviceIntent.putExtra("extra_seconds", seconds);
                        startService(serviceIntent);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }

            @JavascriptInterface
            public void downloadMusicFileDirect(String ytId, String title, String artist) {
                executor.execute(() -> {
                    try {
                        String streamUrl = resolveDirectAudioStream(ytId);
                        if (streamUrl != null && !streamUrl.isEmpty()) {
                            runOnUiThread(() -> {
                                try {
                                    String cleanTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : "Muzik";
                                    cleanTitle = cleanTitle.replaceAll("[^a-zA-Z0-9çÇğĞıİöÖşŞüÜ\\s\\-_]", "").trim();
                                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(streamUrl));
                                    request.setTitle(cleanTitle);
                                    request.setDescription("MüzikCepte internetsiz dinleme dosyası");
                                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, cleanTitle + ".m4a");
                                    if (dm != null) {
                                        dm.enqueue(request);
                                        Toast.makeText(MainActivity.this, "📥 '" + cleanTitle + "' telefonunuza indiriliyor! İndirilenler klasöründe yer alacaktır.", Toast.LENGTH_LONG).show();
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    Toast.makeText(MainActivity.this, "İndirme başlatılamadı: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                }
                            });
                        } else {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, "İndirme bağlantısı alınamadı.", Toast.LENGTH_SHORT).show());
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }

            @JavascriptInterface
            public void showToast(String message) {
                if (message != null && !message.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
                }
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


                public static String resolveDirectAudioStream(String ytId) {
        if (ytId == null || ytId.trim().isEmpty()) return null;
        try {
            String apiUrl = "https://www.youtube.com/youtubei/v1/player?key=AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w";
            JSONObject clientJson = new JSONObject();
            clientJson.put("clientName", "ANDROID");
            clientJson.put("clientVersion", "20.10.38");
            clientJson.put("hl", "tr");
            clientJson.put("gl", "TR");

            JSONObject contextJson = new JSONObject();
            contextJson.put("client", clientJson);

            JSONObject payloadJson = new JSONObject();
            payloadJson.put("context", contextJson);
            payloadJson.put("videoId", ytId.trim());

            byte[] postBytes = payloadJson.toString().getBytes(StandardCharsets.UTF_8);

            HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "com.google.android.youtube/20.10.38 (Linux; U; Android 14; TR) gzip");
            conn.setDoOutput(true);
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(7000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postBytes);
                os.flush();
            }

            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return null;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            conn.disconnect();

            JSONObject root = new JSONObject(sb.toString());
            JSONObject streamingData = root.optJSONObject("streamingData");
            if (streamingData == null) return null;
            JSONArray formats = streamingData.optJSONArray("adaptiveFormats");
            if (formats == null) return null;

            String bestAudioUrl = null;
            int maxBitrate = 0;
            for (int i = 0; i < formats.length(); i++) {
                JSONObject f = formats.getJSONObject(i);
                String mime = f.optString("mimeType", "");
                if (mime.contains("audio/mp4")) {
                    int bitrate = f.optInt("bitrate", 0);
                    String u = f.optString("url", "");
                    if (!u.isEmpty() && bitrate >= maxBitrate) {
                        maxBitrate = bitrate;
                        bestAudioUrl = u;
                    }
                }
            }
            if (bestAudioUrl == null) {
                for (int i = 0; i < formats.length(); i++) {
                    JSONObject f = formats.getJSONObject(i);
                    String mime = f.optString("mimeType", "");
                    if (mime.contains("audio")) {
                        String u = f.optString("url", "");
                        if (!u.isEmpty()) {
                            bestAudioUrl = u;
                            break;
                        }
                    }
                }
            }
            return bestAudioUrl;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void fetchYouTubeInnertubeSearch(String q, JSONArray results, Set<String> seenIds) {
                try {
                    String apiUrl = "https://www.youtube.com/youtubei/v1/search?key=AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w";
                    JSONObject clientJson = new JSONObject();
                    clientJson.put("clientName", "ANDROID");
                    clientJson.put("clientVersion", "20.10.38");
                    clientJson.put("hl", "tr");
                    clientJson.put("gl", "TR");

                    JSONObject contextJson = new JSONObject();
                    contextJson.put("client", clientJson);

                    JSONObject payloadJson = new JSONObject();
                    payloadJson.put("context", contextJson);
                    payloadJson.put("query", q);

                    byte[] postBytes = payloadJson.toString().getBytes(StandardCharsets.UTF_8);

                    HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("User-Agent", "com.google.android.youtube/20.10.38 (Linux; U; Android 14; TR) gzip");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(postBytes);
                        os.flush();
                    }

                    int code = conn.getResponseCode();
                    if (code != 200) {
                        conn.disconnect();
                        return;
                    }

                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                    }
                    conn.disconnect();

                    JSONObject root = new JSONObject(sb.toString());
                    JSONObject contentsObj = root.optJSONObject("contents");
                    if (contentsObj == null) return;
                    JSONObject sectionList = contentsObj.optJSONObject("sectionListRenderer");
                    if (sectionList == null) return;
                    JSONArray sections = sectionList.optJSONArray("contents");
                    if (sections == null) return;

                    for (int s = 0; s < sections.length(); s++) {
                        JSONObject sec = sections.getJSONObject(s);
                        JSONObject itemSection = sec.optJSONObject("itemSectionRenderer");
                        if (itemSection == null) continue;
                        JSONArray items = itemSection.optJSONArray("contents");
                        if (items == null) continue;

                        for (int i = 0; i < items.length(); i++) {
                            JSONObject item = items.getJSONObject(i);
                            JSONObject videoObj = item.optJSONObject("compactVideoRenderer");
                            if (videoObj == null) {
                                videoObj = item.optJSONObject("videoRenderer");
                            }
                            if (videoObj == null) continue;

                            String videoId = videoObj.optString("videoId", "");
                            if (videoId.isEmpty() || seenIds.contains(videoId)) continue;
                            seenIds.add(videoId);

                            String title = "";
                            JSONObject titleObj = videoObj.optJSONObject("title");
                            if (titleObj != null) {
                                JSONArray runs = titleObj.optJSONArray("runs");
                                if (runs != null && runs.length() > 0) {
                                    title = runs.getJSONObject(0).optString("text", "");
                                } else {
                                    title = titleObj.optString("simpleText", "");
                                }
                            }
                            if (title.isEmpty()) continue;

                            String artist = "";
                            JSONObject byline = videoObj.optJSONObject("longBylineText");
                            if (byline == null) byline = videoObj.optJSONObject("shortBylineText");
                            if (byline != null) {
                                JSONArray runs = byline.optJSONArray("runs");
                                if (runs != null && runs.length() > 0) {
                                    artist = runs.getJSONObject(0).optString("text", "");
                                }
                            }
                            if (artist.isEmpty()) artist = "Müzik";

                            String duration = "03:30";
                            JSONObject lengthObj = videoObj.optJSONObject("lengthText");
                            if (lengthObj != null) {
                                JSONArray runs = lengthObj.optJSONArray("runs");
                                if (runs != null && runs.length() > 0) {
                                    duration = runs.getJSONObject(0).optString("text", "03:30");
                                } else {
                                    duration = lengthObj.optString("simpleText", "03:30");
                                }
                            }

                            String art = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";

                            JSONObject song = new JSONObject();
                            song.put("id", videoId);
                            song.put("title", title);
                            song.put("artist", artist);
                            song.put("duration", duration);
                            song.put("art", art);
                            song.put("thumb", art);

                            results.put(song);
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

                        String targetQuery = query;
                        if (page == 2) {
                            targetQuery = query + " şarkıları";
                        } else if (page == 3) {
                            targetQuery = query + " albüm";
                        } else if (page > 3) {
                            targetQuery = query + " hit şarkılar";
                        }

                        fetchYouTubeInnertubeSearch(targetQuery, results, seenIds);

                        runOnUiThread(() -> {
                            if (webView != null) {
                                webView.evaluateJavascript("if(window.onMusicSearchResults) window.onMusicSearchResults(" + results.toString() + ", " + page + ");", null);
                            }
                        });

                    } catch (Exception e) {
                        e.printStackTrace();
                        runOnUiThread(() -> {
                            if (webView != null) {
                                webView.evaluateJavascript("if(window.onMusicSearchError) window.onMusicSearchError('" + e.getMessage() + "');", null);
                            }
                        });
                    }
                }).start();
            }

            @JavascriptInterface
            public void searchOnlineMusic(String query) {
                searchOnlineMusic(query, 1);
            }

            @JavascriptInterface
            public void requestDownloadWithReward(String ytId, String title, String artist) {
                MainActivity.this.requestDownloadWithReward(ytId, title, artist);
            }

            @JavascriptInterface
            public void downloadMusicFile(String ytId, String title, String artist) {
                MainActivity.this.requestDownloadWithReward(ytId, title, artist);
            }

            @JavascriptInterface
            public void downloadMusicFile(String ytId, String title) {
                MainActivity.this.requestDownloadWithReward(ytId, title, "");
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
