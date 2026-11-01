package com.electrocat.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;

import com.getcapacitor.JSObject;

/**
 * Plays the music, keeps playing when the app is closed, and shows the
 * notification with Previous / Pause / Next / Close.
 */
public class PlaybackService extends Service {

    static final String CHANNEL = "electromusic_playback";
    static final int NOTE_ID = 4711;
    static final String A_PLAY = "com.electrocat.music.PLAY";
    static final String A_TOGGLE = "com.electrocat.music.TOGGLE";
    static final String A_NEXT = "com.electrocat.music.NEXT";
    static final String A_PREV = "com.electrocat.music.PREV";
    static final String A_CLOSE = "com.electrocat.music.CLOSE";

    public static PlaybackService instance;

    // The queue is handed over by the plugin (same process).
    static long[] qIds = new long[0];
    static String[] qTitles = new String[0];
    static String[] qArtists = new String[0];
    static int qIndex = 0;
    static boolean qShuffle = false;
    static int qRepeat = 0;

    private MediaPlayer mp;
    private MediaSession session;
    private AudioManager audioManager;
    private boolean prepared = false;
    private boolean playing = false;
    private boolean foreground = false;
    private boolean resumeOnGain = false;
    private int errorStreak = 0;

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int change) {
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                resumeOnGain = false;
                pausePlayback();
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                resumeOnGain = playing;
                pausePlayback();
            } else if (change == AudioManager.AUDIOFOCUS_GAIN && resumeOnGain) {
                resumeOnGain = false;
                resumePlayback();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Music playback", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        session = new MediaSession(this, "ElectroMusic");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                resumePlayback();
            }

            @Override
            public void onPause() {
                pausePlayback();
            }

            @Override
            public void onSkipToNext() {
                next(false);
            }

            @Override
            public void onSkipToPrevious() {
                previous();
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo((int) pos);
            }

            @Override
            public void onStop() {
                closeAll();
            }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (A_PLAY.equals(action)) {
            playIndex(qIndex);
        } else if (A_TOGGLE.equals(action)) {
            if (playing) pausePlayback(); else resumePlayback();
        } else if (A_NEXT.equals(action)) {
            next(false);
        } else if (A_PREV.equals(action)) {
            previous();
        } else if (A_CLOSE.equals(action)) {
            closeAll();
        } else if (qIds.length == 0) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        try {
            if (session != null) {
                session.setActive(false);
                session.release();
            }
        } catch (Exception e) {
            // ignore
        }
        instance = null;
        super.onDestroy();
    }

    // ---------- playback ----------

    void playIndex(int i) {
        if (qIds.length == 0) {
            closeAll();
            return;
        }
        if (i < 0 || i >= qIds.length) i = 0;
        qIndex = i;
        releasePlayer();
        prepared = false;
        playing = true;
        try {
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            android.net.Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, qIds[i]);
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(this, uri);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    prepared = true;
                    errorStreak = 0;
                    if (playing) m.start();
                    updateAll();
                    emitTrack();
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    onTrackEnded();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    onPlayError();
                    return true;
                }
            });
            mp.prepareAsync();
        } catch (Exception e) {
            onPlayError();
        }
        updateAll();
        emitTrack();
    }

    private void onPlayError() {
        errorStreak++;
        ElectroMusicPlugin.emit("error", new JSObject());
        if (errorStreak < qIds.length && qIds.length > 1) {
            next(true);
        } else {
            playing = false;
            releasePlayer();
            updateAll();
            emitState();
        }
    }

    private void onTrackEnded() {
        if (qRepeat == 2) {
            try {
                if (mp != null) {
                    mp.seekTo(0);
                    mp.start();
                }
            } catch (Exception e) {
                // ignore
            }
            return;
        }
        next(true);
    }

    void next(boolean auto) {
        int n = qIds.length;
        if (n == 0) return;
        if (qShuffle && n > 1) {
            int r;
            do {
                r = (int) (Math.random() * n);
            } while (r == qIndex);
            playIndex(r);
            return;
        }
        int i = qIndex + 1;
        if (i >= n) {
            if (qRepeat == 1 || !auto) {
                i = 0;
            } else {
                playing = false;
                try {
                    if (mp != null && mp.isPlaying()) mp.pause();
                } catch (Exception e) {
                    // ignore
                }
                updateAll();
                emitState();
                return;
            }
        }
        playIndex(i);
    }

    void previous() {
        if (qIds.length == 0) return;
        if (position() > 3000) {
            seekTo(0);
            return;
        }
        playIndex(qIndex > 0 ? qIndex - 1 : qIds.length - 1);
    }

    void pausePlayback() {
        try {
            if (mp != null && mp.isPlaying()) mp.pause();
        } catch (Exception e) {
            // ignore
        }
        playing = false;
        updateAll();
        emitState();
    }

    void resumePlayback() {
        try {
            if (mp != null && prepared && !mp.isPlaying()) {
                audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                mp.start();
            }
        } catch (Exception e) {
            // ignore
        }
        playing = true;
        updateAll();
        emitState();
    }

    void seekTo(int ms) {
        try {
            if (mp != null && prepared) mp.seekTo(ms);
        } catch (Exception e) {
            // ignore
        }
        updateAll();
    }

    int position() {
        try {
            return (mp != null && prepared) ? mp.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    int duration() {
        try {
            return (mp != null && prepared) ? mp.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    boolean isPlayingNow() {
        return playing;
    }

    void closeAll() {
        releasePlayer();
        playing = false;
        qIds = new long[0];
        qTitles = new String[0];
        qArtists = new String[0];
        qIndex = 0;
        try {
            stopForeground(true);
        } catch (Exception e) {
            // ignore
        }
        foreground = false;
        try {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTE_ID);
        } catch (Exception e) {
            // ignore
        }
        ElectroMusicPlugin.emit("closed", new JSObject());
        stopSelf();
    }

    private void releasePlayer() {
        try {
            if (mp != null) mp.release();
        } catch (Exception e) {
            // ignore
        }
        mp = null;
        prepared = false;
        try {
            if (audioManager != null) audioManager.abandonAudioFocus(focusListener);
        } catch (Exception e) {
            // ignore
        }
    }

    // ---------- events to the web page ----------

    private void emitTrack() {
        JSObject o = new JSObject();
        o.put("index", qIndex);
        o.put("duration", duration());
        ElectroMusicPlugin.emit("track", o);
        emitState();
    }

    private void emitState() {
        JSObject o = new JSObject();
        o.put("playing", playing);
        ElectroMusicPlugin.emit("state", o);
    }

    // ---------- notification and lock screen ----------

    private String titleNow() {
        return (qIndex >= 0 && qIndex < qTitles.length) ? qTitles[qIndex] : "ElectroMusic";
    }

    private String artistNow() {
        return (qIndex >= 0 && qIndex < qArtists.length) ? qArtists[qIndex] : "";
    }

    private Bitmap logo() {
        try {
            int id = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
            if (id != 0) return BitmapFactory.decodeResource(getResources(), id);
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private PendingIntent action(String action, int code) {
        Intent i = new Intent(this, PlaybackService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, code, i, flags);
    }

    private PendingIntent openApp() {
        Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, i, flags);
    }

    private void updateSession() {
        try {
            PlaybackState.Builder pb = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, position(), 1.0f);
            session.setPlaybackState(pb.build());
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titleNow())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artistNow())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration());
            Bitmap big = logo();
            if (big != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, big);
            session.setMetadata(mb.build());
        } catch (Exception e) {
            // ignore
        }
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL)
            : new Notification.Builder(this);
        String artist = artistNow();
        b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(titleNow())
            .setContentText(artist.isEmpty() ? "ElectroMusic" : artist)
            .setContentIntent(openApp())
            .setDeleteIntent(action(A_CLOSE, 4))
            .setOngoing(playing)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC);
        Bitmap big = logo();
        if (big != null) b.setLargeIcon(big);
        b.addAction(android.R.drawable.ic_media_previous, "Previous", action(A_PREV, 1));
        b.addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
            playing ? "Pause" : "Play", action(A_TOGGLE, 2));
        b.addAction(android.R.drawable.ic_media_next, "Next", action(A_NEXT, 3));
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", action(A_CLOSE, 4));
        b.setStyle(new Notification.MediaStyle()
            .setMediaSession(session.getSessionToken())
            .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private void updateAll() {
        updateSession();
        Notification n = buildNotification();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (playing) {
            if (!foreground) {
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                    } else {
                        startForeground(NOTE_ID, n);
                    }
                    foreground = true;
                } catch (Exception e) {
                    nm.notify(NOTE_ID, n);
                }
            } else {
                nm.notify(NOTE_ID, n);
            }
        } else {
            if (foreground) {
                try {
                    stopForeground(false);
                } catch (Exception e) {
                    // ignore
                }
                foreground = false;
            }
            nm.notify(NOTE_ID, n);
        }
    }
}
