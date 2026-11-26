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
import android.os.Build;
import android.os.IBinder;
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
    private MediaSessionCompat mediaSession;

    public static final String ACTION_START = "com.muzikcepte.app.START_PLAYBACK";
    public static final String ACTION_STOP = "com.muzikcepte.app.STOP_PLAYBACK";
    public static final String ACTION_TOGGLE_PLAY = "com.muzikcepte.app.TOGGLE_PLAY";
    public static final String ACTION_NEXT = "com.muzikcepte.app.NEXT";
    public static final String ACTION_PREV = "com.muzikcepte.app.PREV";
    public static final String ACTION_PLAY_RADIO = "com.muzikcepte.app.PLAY_RADIO";

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

    // Native C++ Android MediaPlayer for 100% stutter-free live radio streams
    private MediaPlayer nativeRadioPlayer;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MuzikCepte::PlaybackWakelock");
            wakeLock.setReferenceCounted(false);
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
                if (!isRadioMode) {
                    currentPositionMs = pos;
                    if (MainActivity.instance != null) {
                        MainActivity.instance.seekToPosition(pos);
                    }
                    updateMediaSession();
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
        if (isRadioMode && nativeRadioPlayer != null) {
            if (nativeRadioPlayer.isPlaying()) {
                nativeRadioPlayer.pause();
                isPlaying = false;
            } else {
                nativeRadioPlayer.start();
                isPlaying = true;
            }
            updateMediaSession();
            updateNotification();
            if (MainActivity.instance != null) {
                MainActivity.instance.updateJsRadioState(isPlaying);
            }
        } else {
            if (MainActivity.instance != null) {
                MainActivity.instance.togglePlayPause();
            }
            isPlaying = !isPlaying;
            updateMediaSession();
            updateNotification();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Müzik & Canlı Radyo Çalma Servisi",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("MüzikCepte arka planda kilit ekranı müzik kontrolleri");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
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

            playNativeRadio(radioUrl);
            startOrUpdateNotification();
            return START_STICKY;
        }

        // Standard Track Playback
        stopNativeRadio();
        isRadioMode = false;

        String t = intent.getStringExtra(EXTRA_TITLE);
        String a = intent.getStringExtra(EXTRA_ARTIST);
        String art = intent.getStringExtra(EXTRA_ART_URL);

        if (t != null && !t.isEmpty()) currentTitle = t;
        if (a != null && !a.isEmpty()) currentArtist = a;
        if (intent.hasExtra(EXTRA_IS_PLAYING)) {
            isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, true);
        } else {
            isPlaying = true;
        }

        if (intent.hasExtra(EXTRA_DURATION)) {
            currentDurationMs = intent.getLongExtra(EXTRA_DURATION, currentDurationMs);
        }
        if (intent.hasExtra(EXTRA_POSITION)) {
            currentPositionMs = intent.getLongExtra(EXTRA_POSITION, currentPositionMs);
        }

        if (art != null && !art.isEmpty() && !art.equals(currentArtUrl)) {
            currentArtUrl = art;
            fetchArtBitmap(currentArtUrl);
        }

        startOrUpdateNotification();
        return START_STICKY;
    }

    private void playNativeRadio(String url) {
        stopNativeRadio();
        if (url == null || url.isEmpty()) return;
        try {
            nativeRadioPlayer = new MediaPlayer();
            nativeRadioPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            nativeRadioPlayer.setDataSource(url);
            nativeRadioPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            nativeRadioPlayer.setOnPreparedListener(mp -> {
                mp.start();
                isPlaying = true;
                updateMediaSession();
                updateNotification();
            });
            nativeRadioPlayer.setOnErrorListener((mp, what, extra) -> {
                stopNativeRadio();
                return true;
            });
            nativeRadioPlayer.prepareAsync();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopNativeRadio() {
        if (nativeRadioPlayer != null) {
            try {
                if (nativeRadioPlayer.isPlaying()) {
                    nativeRadioPlayer.stop();
                }
                nativeRadioPlayer.reset();
                nativeRadioPlayer.release();
            } catch (Exception ignored) {}
            nativeRadioPlayer = null;
        }
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
                InputStream input = conn.getInputStream();
                Bitmap b = BitmapFactory.decodeStream(input);
                if (b != null) {
                    currentArtBitmap = b;
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
        updateMediaSession();
        updateNotification();
    }

    private void updateMediaSession() {
        if (mediaSession == null) return;

        long actions = PlaybackStateCompat.ACTION_PLAY |
                PlaybackStateCompat.ACTION_PAUSE |
                PlaybackStateCompat.ACTION_PLAY_PAUSE |
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                PlaybackStateCompat.ACTION_STOP;

        if (!isRadioMode) {
            actions |= PlaybackStateCompat.ACTION_SEEK_TO;
        }

        int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        PlaybackStateCompat playbackState = new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, currentPositionMs, 1.0f)
                .build();
        mediaSession.setPlaybackState(playbackState);

        MediaMetadataCompat.Builder metaBuilder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, isRadioMode ? "Canlı Radyo" : "MüzikCepte")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, isRadioMode ? -1 : currentDurationMs);

        if (currentArtBitmap != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArtBitmap);
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, currentArtBitmap);
        }

        mediaSession.setMetadata(metaBuilder.build());
    }

    private void updateNotification() {
        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openAppIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Intent prevIntent = new Intent(this, MusicPlaybackService.class).setAction(ACTION_PREV);
        PendingIntent prevPending = PendingIntent.getService(
                this, 1, prevIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Intent playPauseIntent = new Intent(this, MusicPlaybackService.class).setAction(ACTION_TOGGLE_PLAY);
        PendingIntent playPausePending = PendingIntent.getService(
                this, 2, playPauseIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Intent nextIntent = new Intent(this, MusicPlaybackService.class).setAction(ACTION_NEXT);
        PendingIntent nextPending = PendingIntent.getService(
                this, 3, nextIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Intent stopIntent = new Intent(this, MusicPlaybackService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 4, stopIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        int playPauseIcon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String playPauseTitle = isPlaying ? "Durdur" : "Oynat";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(currentTitle)
                .setContentText(currentArtist)
                .setSubText(isRadioMode ? "Canlı Radyo" : "MüzikCepte")
                .setContentIntent(pendingIntent)
                .setOngoing(isPlaying)
                .setSilent(true)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2)
                        .setShowCancelButton(true)
                        .setCancelButtonIntent(stopPending)
                )
                .addAction(android.R.drawable.ic_media_previous, "Önceki", prevPending)
                .addAction(playPauseIcon, playPauseTitle, playPausePending)
                .addAction(android.R.drawable.ic_media_next, "Sonraki", nextPending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kapat", stopPending);

        if (currentArtBitmap != null) {
            builder.setLargeIcon(currentArtBitmap);
        }

        Notification notification = builder.build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopPlayback() {
        stopNativeRadio();
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {}
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
        }
        stopForeground(true);
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
