#!/bin/bash
# Adds the music engine to the Android project (run by the build file).
set -e
DIR=android/app/src/main/java/com/electrocat/music
cp ElectroMusicPlugin.java PlaybackService.java "$DIR/"

cat > "$DIR/MainActivity.java" <<'JAVA'
package com.electrocat.music;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ElectroMusicPlugin.class);
        super.onCreate(savedInstanceState);
        getBridge().getWebView().getSettings().setMediaPlaybackRequiresUserGesture(false);
    }
}
JAVA

MF=android/app/src/main/AndroidManifest.xml
sed -i 's|<application|<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />\n    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />\n    <uses-permission android:name="android.permission.WAKE_LOCK" />\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />\n    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n    <application|' "$MF"
sed -i 's|</application>|    <service android:name="com.electrocat.music.PlaybackService" android:exported="false" android:foregroundServiceType="mediaPlayback" />\n    </application>|' "$MF"
echo "music engine added"
