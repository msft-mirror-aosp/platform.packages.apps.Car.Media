/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.media.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleService;

import com.android.car.media.R;
import com.android.car.media.common.playback.PlaybackViewModel;

/**
 * This service is started by CarMediaService when a new user is unlocked. It connects to the
 * media source provided by CarMediaService and calls prepare() on the active MediaSession.
 *
 * TODO(b/139497602): merge this class into CarMediaService, so it doesn't depend on Media Center
 */
public class MediaConnectorService extends LifecycleService {

    private static int FOREGROUND_NOTIFICATION_ID = 1;
    private static String NOTIFICATION_CHANNEL_ID = "com.android.car.media.service";
    private static String NOTIFICATION_CHANNEL_NAME = "MediaConnectorService";

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_NONE);
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        PlaybackViewModel playbackViewModel = PlaybackViewModel.get(getApplication());
        playbackViewModel.getPlaybackController().observe(this,
                playbackController -> {
                    if (playbackController != null) {
                        playbackController.prepare();
                        // Stop this service after we've successfully connected to the
                        // MediaBrowser, since we no longer need to do anything.
                        stopSelf(startId);
                    }
                });

        // Since this service is started from CarMediaService (which runs in background), we need
        // to call startForeground to prevent the system from stopping this service and ANRing.
        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music)
                .setContentTitle(getResources().getString(R.string.service_notification_title))
                .build();
        startForeground(FOREGROUND_NOTIFICATION_ID, notification);
        return START_NOT_STICKY;
    }
}
