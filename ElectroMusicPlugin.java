package com.electrocat.music;

import android.Manifest;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.provider.MediaStore;

import androidx.activity.OnBackPressedCallback;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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

    private static ElectroMusicPlugin inst;
    private boolean guarded = false;

    @Override
    public void load() {
        inst = this;
    }

    @Override
    protected void handleOnDestroy() {
        if (inst == this) inst = null;
        super.handleOnDestroy();
    }

    // Called by the playback service to tell the web page what is happening.
    public static void emit(String name, JSObject data) {
        ElectroMusicPlugin p = inst;
        if (p != null) {
            try {
                p.notifyListeners(name, data);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    private String permAlias() {
        return Build.VERSION.SDK_INT >= 33 ? "audio" : "storage";
    }

    // ---------- finding music ----------

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

    // ---------- back button and exit ----------

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

    // ---------- playing ----------

    @PluginMethod
    public void lock(PluginCall call) {
        call.resolve();
    }

    @PluginMethod
    public void unlock(PluginCall call) {
        call.resolve();
    }

    @PluginMethod
    public void playQueue(final PluginCall call) {
        try {
            JSArray ids = call.getArray("ids");
            JSArray titles = call.getArray("titles");
            JSArray artists = call.getArray("artists");
            int n = ids.length();
            long[] qi = new long[n];
            String[] qt = new String[n];
            String[] qa = new String[n];
            for (int i = 0; i < n; i++) {
                qi[i] = ids.getLong(i);
                qt[i] = titles.isNull(i) ? "" : titles.getString(i);
                qa[i] = artists.isNull(i) ? "" : artists.getString(i);
            }
            PlaybackService.qIds = qi;
            PlaybackService.qTitles = qt;
            PlaybackService.qArtists = qa;
            Integer index = call.getInt("index");
            PlaybackService.qIndex = index == null ? 0 : index;
            Boolean shuffle = call.getBoolean("shuffle");
            PlaybackService.qShuffle = shuffle != null && shuffle;
            Integer repeat = call.getInt("repeat");
            PlaybackService.qRepeat = repeat == null ? 0 : repeat;
        } catch (Exception e) {
            call.reject("bad queue");
            return;
        }
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Build.VERSION.SDK_INT >= 33
                        && ContextCompat.checkSelfPermission(getContext(), "android.permission.POST_NOTIFICATIONS")
                            != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(getActivity(),
                            new String[] { "android.permission.POST_NOTIFICATIONS" }, 4242);
                    }
                    Intent si = new Intent(getContext(), PlaybackService.class).setAction(PlaybackService.A_PLAY);
                    if (Build.VERSION.SDK_INT >= 26) {
                        getContext().startForegroundService(si);
                    } else {
                        getContext().startService(si);
                    }
                    call.resolve();
                } catch (Exception e) {
                    call.reject(e.getMessage() == null ? "could not start" : e.getMessage());
                }
            }
        });
    }

    @PluginMethod
    public void nextTrack(final PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                PlaybackService s = PlaybackService.instance;
                if (s != null) s.next(false);
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void prevTrack(final PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                PlaybackService s = PlaybackService.instance;
                if (s != null) s.previous();
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void resume(final PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                PlaybackService s = PlaybackService.instance;
                if (s != null) s.resumePlayback();
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void pause(final PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                PlaybackService s = PlaybackService.instance;
                if (s != null) s.pausePlayback();
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
                PlaybackService s = PlaybackService.instance;
                if (s != null && ms != null) s.seekTo(ms);
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void setModes(PluginCall call) {
        Boolean shuffle = call.getBoolean("shuffle");
        Integer repeat = call.getInt("repeat");
        PlaybackService.qShuffle = shuffle != null && shuffle;
        PlaybackService.qRepeat = repeat == null ? 0 : repeat;
        call.resolve();
    }

    @PluginMethod
    public void status(final PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                JSObject r = new JSObject();
                PlaybackService s = PlaybackService.instance;
                if (s != null) {
                    r.put("position", s.position());
                    r.put("duration", s.duration());
                    r.put("playing", s.isPlayingNow());
                    r.put("index", PlaybackService.qIndex);
                }
                call.resolve(r);
            }
        });
    }

    @PluginMethod
    public void getState(final PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                JSObject r = new JSObject();
                PlaybackService s = PlaybackService.instance;
                if (s == null || PlaybackService.qIds.length == 0) {
                    r.put("active", false);
                } else {
                    JSArray ids = new JSArray();
                    for (long id : PlaybackService.qIds) ids.put(id);
                    r.put("active", true);
                    r.put("ids", ids);
                    r.put("index", PlaybackService.qIndex);
                    r.put("playing", s.isPlayingNow());
                    r.put("shuffle", PlaybackService.qShuffle);
                    r.put("repeat", PlaybackService.qRepeat);
                    r.put("position", s.position());
                    r.put("duration", s.duration());
                }
                call.resolve(r);
            }
        });
    }

    // ---------- sharing ----------

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
