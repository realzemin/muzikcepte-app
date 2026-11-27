package com.muzikcepte.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MusicPlaybackService extends Service {
    private static final String CHANNEL_ID = "muzikcepte_playback_channel";
    private static final int NOTIFICATION_ID = 101;
    private PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private MediaSessionCompat mediaSession;

    public static final String ACTION_START = "com.muzikcepte.app.START_PLAYBACK";
    public static final String ACTION_STOP = "com.muzikcepte.app.STOP_PLAYBACK";
    public static final String ACTION_TOGGLE_PLAY = "com.muzikcepte.app.TOGGLE_PLAY";
    public static final String ACTION_NEXT = "com.muzikcepte.app.NEXT";
    public static final String ACTION_PREV = "com.muzikcepte.app.PREV";
    public static final String ACTION_PLAY_RADIO = "com.muzikcepte.app.PLAY_RADIO";
    public static final String ACTION_PLAY_TRACK = "com.muzikcepte.app.PLAY_TRACK";
    public static final String ACTION_SEEK_TO = "com.muzikcepte.app.SEEK_TO";
    public static final String ACTION_SKIP_SECONDS = "com.muzikcepte.app.SKIP_SECONDS";

    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_ARTIST = "extra_artist";
    public static final String EXTRA_IS_PLAYING = "extra_is_playing";
    public static final String EXTRA_ART_URL = "extra_art_url";
    public static final String EXTRA_DURATION = "extra_duration";
    public static final String EXTRA_POSITION = "extra_position";

    private String currentTitle = "Müzik Çalıyor";
    private String currentArtist = "MüzikCepte";
    private String currentArtUrl = "";
    private Bitmap currentArtBitmap = null;
    private boolean isPlaying = true;
    private boolean isRadioMode = false;
    private long currentDurationMs = 210000;
    private long currentPositionMs = 0;

    // Pure Native C++ Android MediaPlayer for 100% stutter-free audio & radio streams
    private MediaPlayer nativePlayer;

    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (nativePlayer != null && isPlaying && !isRadioMode) {
                try {
                    int cur = nativePlayer.getCurrentPosition();
                    int dur = nativePlayer.getDuration();
                    if (dur > 0) {
                        currentDurationMs = dur;
                    }
                    currentPositionMs = cur;
                    if (MainActivity.instance != null) {
                        MainActivity.instance.updatePlaybackProgress(cur / 1000, currentDurationMs / 1000);
                    }
                } catch (Exception ignored) {}
                progressHandler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MuzikCepte::PlaybackWakelock");
            wakeLock.setReferenceCounted(false);
        }

        android.net.wifi.WifiManager wifiManager = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            wifiLock = wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MuzikCepte::PlaybackWifiLock");
            wifiLock.setReferenceCounted(false);
        }

        initMediaSession();
    }

    private void initMediaSession() {
        mediaSession = new MediaSessionCompat(this, "MuzikCepteMediaSession");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                toggleNativeOrJsPlay();
            }

            @Override
            public void onPause() {
                toggleNativeOrJsPlay();
            }

            @Override
            public void onSkipToNext() {
                if (MainActivity.instance != null) {
                    MainActivity.instance.playNextTrack();
                }
                isPlaying = true;
                updateMediaSession();
                updateNotification();
            }

            @Override
            public void onSkipToPrevious() {
                if (MainActivity.instance != null) {
                    MainActivity.instance.playPrevTrack();
                }
                isPlaying = true;
                updateMediaSession();
                updateNotification();
            }

            @Override
            public void onSeekTo(long pos) {
                if (nativePlayer != null && !isRadioMode) {
                    try {
                        nativePlayer.seekTo((int) pos);
                        currentPositionMs = pos;
                        updateMediaSession();
                    } catch (Exception ignored) {}
                }
            }

            @Override
            public void onStop() {
                stopPlayback();
                stopSelf();
            }
        });

        mediaSession.setActive(true);
    }

    private void toggleNativeOrJsPlay() {
        if (nativePlayer != null) {
            try {
                if (nativePlayer.isPlaying()) {
                    nativePlayer.pause();
                    isPlaying = false;
                    stopProgressReporting();
                } else {
                    nativePlayer.start();
                    isPlaying = true;
                    startProgressReporting();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            updateMediaSession();
            updateNotification();
            if (MainActivity.instance != null) {
                if (isRadioMode) {
                    MainActivity.instance.updateJsRadioState(isPlaying);
                } else {
                    MainActivity.instance.updateJsTrackState(isPlaying, currentDurationMs);
                }
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Müzik Çalma Bildirimi",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("MüzikCepte arka plan oynatma ve kilit ekranı kontrolleri");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopPlayback();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_TOGGLE_PLAY.equals(action)) {
            toggleNativeOrJsPlay();
            return START_STICKY;
        }

        if (ACTION_NEXT.equals(action)) {
            if (MainActivity.instance != null) {
                MainActivity.instance.playNextTrack();
            }
            isPlaying = true;
            updateMediaSession();
            updateNotification();
            return START_STICKY;
        }

        if (ACTION_PREV.equals(action)) {
            if (MainActivity.instance != null) {
                MainActivity.instance.playPrevTrack();
            }
            isPlaying = true;
            updateMediaSession();
            updateNotification();
            return START_STICKY;
        }

        if (ACTION_SEEK_TO.equals(action)) {
            long pos = intent.getLongExtra(EXTRA_POSITION, 0);
            if (nativePlayer != null && !isRadioMode) {
                try {
                    nativePlayer.seekTo((int) pos);
                    currentPositionMs = pos;
                    updateMediaSession();
                } catch (Exception ignored) {}
            }
            return START_STICKY;
        }

        if (ACTION_SKIP_SECONDS.equals(action)) {
            long sec = intent.getLongExtra("extra_seconds", 0);
            if (nativePlayer != null && !isRadioMode) {
                try {
                    int cur = nativePlayer.getCurrentPosition();
                    int dur = nativePlayer.getDuration();
                    int target = (int) Math.max(0, Math.min(cur + sec * 1000L, dur));
                    nativePlayer.seekTo(target);
                    currentPositionMs = target;
                    updateMediaSession();
                } catch (Exception ignored) {}
            }
            return START_STICKY;
        }

        if (ACTION_PLAY_RADIO.equals(action)) {
            isRadioMode = true;
            String radioUrl = intent.getStringExtra("radio_url");
            String t = intent.getStringExtra(EXTRA_TITLE);
            String a = intent.getStringExtra(EXTRA_ARTIST);
            String art = intent.getStringExtra(EXTRA_ART_URL);

            if (t != null && !t.isEmpty()) currentTitle = t;
            if (a != null && !a.isEmpty()) currentArtist = a;
            isPlaying = true;

            if (art != null && !art.isEmpty() && !art.equals(currentArtUrl)) {
                currentArtUrl = art;
                fetchArtBitmap(currentArtUrl);
            }

            playNativeAudio(radioUrl, true);
            startOrUpdateNotification();
            return START_STICKY;
        }

        if (ACTION_PLAY_TRACK.equals(action)) {
            isRadioMode = false;
            String streamUrl = intent.getStringExtra("stream_url");
            String t = intent.getStringExtra(EXTRA_TITLE);
            String a = intent.getStringExtra(EXTRA_ARTIST);
            String art = intent.getStringExtra(EXTRA_ART_URL);

            if (t != null && !t.isEmpty()) currentTitle = t;
            if (a != null && !a.isEmpty()) currentArtist = a;
            isPlaying = true;

            if (art != null && !art.isEmpty() && !art.equals(currentArtUrl)) {
                currentArtUrl = art;
                fetchArtBitmap(currentArtUrl);
            }

            playNativeAudio(streamUrl, false);
            startOrUpdateNotification();
            return START_STICKY;
        }

        // Standard notification update fallback
        String t = intent.getStringExtra(EXTRA_TITLE);
        String a = intent.getStringExtra(EXTRA_ARTIST);
        String art = intent.getStringExtra(EXTRA_ART_URL);

        if (t != null && !t.isEmpty()) currentTitle = t;
        if (a != null && !a.isEmpty()) currentArtist = a;
        if (intent.hasExtra(EXTRA_IS_PLAYING)) {
            isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, true);
        }

        if (art != null && !art.isEmpty() && !art.equals(currentArtUrl)) {
            currentArtUrl = art;
            fetchArtBitmap(currentArtUrl);
        }

        startOrUpdateNotification();
        return START_STICKY;
    }

    private void playNativeAudio(String url, boolean isRadio) {
        stopNativePlayer();
        if (url == null || url.isEmpty()) return;
        try {
            nativePlayer = new MediaPlayer();
            nativePlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());

            if (isRadio) {
                java.util.Map<String, String> headers = new java.util.HashMap<>();
                headers.put("User-Agent", "VLC/3.0.18 (Linux; Android 14)");
                headers.put("Icy-MetaData", "0");
                headers.put("Accept", "*/*");
                try {
                    nativePlayer.setDataSource(this, Uri.parse(url), headers);
                } catch (Exception e1) {
                    try {
                        nativePlayer.setDataSource(url);
                    } catch (Exception e2) {
                        e2.printStackTrace();
                    }
                }
            } else {
                if (url.startsWith("/")) {
                    nativePlayer.setDataSource(url);
                } else {
                    nativePlayer.setDataSource(this, Uri.parse(url));
                }
            }

            nativePlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            nativePlayer.setOnPreparedListener(mp -> {
                try {
                    mp.start();
                    isPlaying = true;
                    if (!isRadio) {
                        currentDurationMs = mp.getDuration();
                        startProgressReporting();
                    }
                    updateMediaSession();
                    updateNotification();
                    if (MainActivity.instance != null) {
                        if (isRadio) {
                            MainActivity.instance.updateJsRadioState(true);
                        } else {
                            MainActivity.instance.updateJsTrackState(true, currentDurationMs);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            nativePlayer.setOnCompletionListener(mp -> {
                if (!isRadio) {
                    if (MainActivity.instance != null) {
                        MainActivity.instance.playNextTrack();
                    }
                }
            });

            nativePlayer.setOnErrorListener((mp, what, extra) -> {
                stopNativePlayer();
                isPlaying = false;
                updateMediaSession();
                updateNotification();
                stopProgressReporting();
                if (MainActivity.instance != null) {
                    if (isRadio) {
                        MainActivity.instance.updateJsRadioState(false);
                    } else {
                        MainActivity.instance.updateJsTrackState(false, 0);
                    }
                }
                return true;
            });

            nativePlayer.prepareAsync();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopNativePlayer() {
        stopProgressReporting();
        if (nativePlayer != null) {
            try {
                if (nativePlayer.isPlaying()) {
                    nativePlayer.stop();
                }
                nativePlayer.reset();
                nativePlayer.release();
            } catch (Exception ignored) {}
            nativePlayer = null;
        }
    }

    private void startProgressReporting() {
        stopProgressReporting();
        if (!isRadioMode) {
            progressHandler.post(progressRunnable);
        }
    }

    private void stopProgressReporting() {
        progressHandler.removeCallbacks(progressRunnable);
    }

    private void fetchArtBitmap(String urlStr) {
        new Thread(() -> {
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setDoInput(true);
                conn.connect();
                InputStream is = conn.getInputStream();
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (bmp != null) {
                    currentArtBitmap = bmp;
                    updateMediaSession();
                    updateNotification();
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void startOrUpdateNotification() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(12 * 60 * 60 * 1000L); // 12 hours safe wakelock
        }
        if (wifiLock != null && !wifiLock.isHeld()) {
            wifiLock.acquire();
        }
        updateMediaSession();
        updateNotification();
    }

    private void updateMediaSession() {
        if (mediaSession == null) return;

        MediaMetadataCompat.Builder metaBuilder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, isRadioMode ? -1 : currentDurationMs);

        if (currentArtBitmap != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArtBitmap);
        }

        mediaSession.setMetadata(metaBuilder.build());

        int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_STOP;

        if (!isRadioMode) {
            actions |= PlaybackStateCompat.ACTION_SEEK_TO;
        }

        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, currentPositionMs, 1.0f);

        mediaSession.setPlaybackState(stateBuilder.build());
    }

    private void updateNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Intent prevIntent = new Intent(this, MusicPlaybackService.class).setAction(ACTION_PREV);
        PendingIntent prevPendingIntent = PendingIntent.getService(
                this, 1, prevIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Intent playPauseIntent = new Intent(this, MusicPlaybackService.class).setAction(ACTION_TOGGLE_PLAY);
        PendingIntent playPausePendingIntent = PendingIntent.getService(
                this, 2, playPauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Intent nextIntent = new Intent(this, MusicPlaybackService.class).setAction(ACTION_NEXT);
        PendingIntent nextPendingIntent = PendingIntent.getService(
                this, 3, nextIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Intent stopIntent = new Intent(this, MusicPlaybackService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 4, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        int playIcon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String playTitle = isPlaying ? "Duraklat" : "Oynat";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(currentTitle)
                .setContentText(currentArtist)
                .setContentIntent(contentPendingIntent)
                .setOngoing(isPlaying)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(android.R.drawable.ic_media_previous, "Önceki", prevPendingIntent)
                .addAction(playIcon, playTitle, playPausePendingIntent)
                .addAction(android.R.drawable.ic_media_next, "Sonraki", nextPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kapat", stopPendingIntent);

        if (currentArtBitmap != null) {
            builder.setLargeIcon(currentArtBitmap);
        }

        MediaStyle style = new MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2);

        builder.setStyle(style);

        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void stopPlayback() {
        isPlaying = false;
        stopNativePlayer();
        stopProgressReporting();
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {}
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            try {
                wifiLock.release();
            } catch (Exception ignored) {}
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
        }
        if (MainActivity.instance != null) {
            MainActivity.instance.stopAudioEngine();
        }
        try {
            stopForeground(true);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTIFICATION_ID);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopPlayback();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopPlayback();
        if (mediaSession != null) {
            mediaSession.release();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
