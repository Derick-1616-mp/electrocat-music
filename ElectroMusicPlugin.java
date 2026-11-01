package com.electrocat.music;

import android.Manifest;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.PowerManager;
import android.provider.MediaStore;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(
    name = "Music",
    permissions = {
        @Permission(alias = "audio", strings = { "android.permission.READ_MEDIA_AUDIO" }),
        @Permission(alias = "storage", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
    }
)
public class ElectroMusicPlugin extends Plugin {

    private PowerManager.WakeLock wakeLock;
    private boolean guarded = false;
    private MediaPlayer mp;
    private AudioManager audioManager;

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int change) {
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                try {
                    if (mp != null && mp.isPlaying()) {
                        mp.pause();
                        notifyListeners("paused", new JSObject());
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    };

    private String permAlias() {
        return Build.VERSION.SDK_INT >= 33 ? "audio" : "storage";
    }

    private void releasePlayer() {
        try {
            if (mp != null) mp.release();
        } catch (Exception e) {
            // ignore
        }
        mp = null;
        try {
            if (audioManager != null) audioManager.abandonAudioFocus(focusListener);
        } catch (Exception e) {
            // ignore
        }
    }

    @Override
    protected void handleOnDestroy() {
        releasePlayer();
        super.handleOnDestroy();
    }

    @PluginMethod
    public void scan(PluginCall call) {
        if (getPermissionState(permAlias()) != PermissionState.GRANTED) {
            requestPermissionForAlias(permAlias(), call, "permDone");
        } else {
            doScan(call);
        }
    }

    @PermissionCallback
    private void permDone(PluginCall call) {
        if (getPermissionState(permAlias()) == PermissionState.GRANTED) {
            doScan(call);
        } else {
            call.reject("denied");
        }
    }

    // The Android Back button is sent to the web page, which decides what to do.
    @PluginMethod
    public void backGuard(final PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!guarded) {
                    guarded = true;
                    getActivity().getOnBackPressedDispatcher().addCallback(getActivity(), new OnBackPressedCallback(true) {
                        @Override
                        public void handleOnBackPressed() {
                            notifyListeners("back", new JSObject());
                        }
                    });
                }
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void exit(final PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                getActivity().finish();
            }
        });
        call.resolve();
    }

    @PluginMethod
    public void lock(PluginCall call) {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "electrocat:music");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire(6 * 60 * 60 * 1000L);
        } catch (Exception e) {
            // keep playing even if the lock could not be taken
        }
        call.resolve();
    }

    @PluginMethod
    public void unlock(PluginCall call) {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception e) {
            // ignore
        }
        call.resolve();
    }

    @PluginMethod
    public void load(final PluginCall call) {
        final String idText = call.getString("id");
        if (idText == null) {
            call.reject("no id");
            return;
        }
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final boolean[] done = { false };
                try {
                    long id = Long.parseLong(idText);
                    android.net.Uri uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                    releasePlayer();
                    audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
                    audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                    mp = new MediaPlayer();
                    mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                    mp.setWakeMode(getContext(), PowerManager.PARTIAL_WAKE_LOCK);
                    mp.setDataSource(getContext(), uri);
                    mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(MediaPlayer m) {
                            m.start();
                            JSObject r = new JSObject();
                            r.put("duration", m.getDuration());
                            if (!done[0]) {
                                done[0] = true;
                                call.resolve(r);
                            }
                        }
                    });
                    mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                        @Override
                        public void onCompletion(MediaPlayer m) {
                            notifyListeners("ended", new JSObject());
                        }
                    });
                    mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        @Override
                        public boolean onError(MediaPlayer m, int what, int extra) {
                            if (!done[0]) {
                                done[0] = true;
                                call.reject("play error " + what);
                            } else {
                                notifyListeners("error", new JSObject());
                            }
                            return true;
                        }
                    });
                    mp.prepareAsync();
                } catch (Exception e) {
                    if (!done[0]) {
                        done[0] = true;
                        call.reject(e.getMessage() == null ? "load failed" : e.getMessage());
                    }
                }
            }
        });
    }

    @PluginMethod
    public void resume(final PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mp != null && !mp.isPlaying()) mp.start();
                } catch (Exception e) {
                    // ignore
                }
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void pause(final PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mp != null && mp.isPlaying()) mp.pause();
                } catch (Exception e) {
                    // ignore
                }
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void seek(final PluginCall call) {
        final Integer ms = call.getInt("ms");
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mp != null && ms != null) mp.seekTo(ms);
                } catch (Exception e) {
                    // ignore
                }
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void status(final PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                JSObject r = new JSObject();
                try {
                    if (mp != null) {
                        r.put("position", mp.getCurrentPosition());
                        r.put("duration", mp.getDuration());
                        r.put("playing", mp.isPlaying());
                    }
                } catch (Exception e) {
                    // ignore
                }
                call.resolve(r);
            }
        });
    }

    @PluginMethod
    public void share(final PluginCall call) {
        final String idText = call.getString("id");
        if (idText == null) {
            call.reject("no id");
            return;
        }
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    long id = Long.parseLong(idText);
                    android.net.Uri uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("audio/*");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.setClipData(ClipData.newRawUri("", uri));
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Intent chooser = Intent.createChooser(send, "Share song");
                    chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    getActivity().startActivity(chooser);
                    call.resolve();
                } catch (Exception e) {
                    call.reject(e.getMessage() == null ? "share failed" : e.getMessage());
                }
            }
        });
    }

    private void doScan(PluginCall call) {
        Cursor c = null;
        try {
            String[] proj = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DATE_ADDED
            };
            String sel = MediaStore.Audio.Media.IS_RINGTONE + " = 0 AND "
                + MediaStore.Audio.Media.IS_NOTIFICATION + " = 0 AND "
                + MediaStore.Audio.Media.IS_ALARM + " = 0";
            c = getContext().getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                proj, sel, null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC");
            JSArray songs = new JSArray();
            if (c != null) {
                while (c.moveToNext()) {
                    String artist = c.getString(2);
                    if (artist != null && artist.equals("<unknown>")) artist = "";
                    String album = c.getString(7);
                    if (album != null && album.equals("<unknown>")) album = "";
                    String path = c.getString(5);
                    JSObject o = new JSObject();
                    o.put("id", c.getLong(0));
                    o.put("title", c.getString(1));
                    o.put("artist", artist == null ? "" : artist);
                    o.put("duration", c.getLong(3));
                    o.put("size", c.getLong(4));
                    o.put("path", path == null ? "" : path);
                    o.put("name", c.getString(6));
                    o.put("album", album == null ? "" : album);
                    o.put("added", c.getLong(8));
                    songs.put(o);
                }
            }
            JSObject res = new JSObject();
            res.put("songs", songs);
            call.resolve(res);
        } catch (Exception e) {
            call.reject(e.getMessage() == null ? "scan failed" : e.getMessage());
        } finally {
            if (c != null) c.close();
        }
    }
}
