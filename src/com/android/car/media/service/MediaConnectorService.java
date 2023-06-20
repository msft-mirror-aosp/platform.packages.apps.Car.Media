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

import static android.car.media.CarMediaIntents.EXTRA_MEDIA_COMPONENT;
import static android.car.media.CarMediaManager.MEDIA_SOURCE_MODE_PLAYBACK;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleService;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.android.car.media.R;
import com.android.car.media.common.playback.PlaybackViewModel;
import com.android.car.media.common.playback.PlaybackViewModel.PlaybackStateWrapper;

import java.util.Objects;

/**
 * This service is started by CarMediaService when a new user is unlocked. It connects to the
 * media source provided by CarMediaService and calls prepare() on the active MediaSession.
 * Additionally, CarMediaService can instruct this service to autoplay, in which case this service
 * will attempt to play the source before stopping.
 */
public class MediaConnectorService extends LifecycleService {

    private static final String TAG = "MediaConnectorSvc";

    private static final int FOREGROUND_NOTIFICATION_ID = 1;
    private static final String NOTIFICATION_CHANNEL_ID = "com.android.car.media.service";
    private static final String NOTIFICATION_CHANNEL_NAME = "MediaConnectorService";
    private static final String EXTRA_AUTOPLAY = "com.android.car.media.autoplay";


    private static class TaskInfo {
        private final int mStartId;
        private final ComponentName mMediaComp;
        private final boolean mAutoPlay;

        TaskInfo(int startId, ComponentName mediaComp, boolean autoPlay) {
            mStartId = startId;
            mMediaComp = mediaComp;
            mAutoPlay = autoPlay;
        }
    }

    private LiveData<PlaybackStateWrapper> mPlaybackLiveData;
    private TaskInfo mCurrentTask;

    @SuppressWarnings("unused")
    public MediaConnectorService() {
        mPlaybackLiveData = null;
    }

    @VisibleForTesting
    public MediaConnectorService(LiveData<PlaybackStateWrapper> playbackLiveData) {
        mPlaybackLiveData = playbackLiveData;
    }

    @VisibleForTesting
    @Override
    public void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (mPlaybackLiveData == null) {
            mPlaybackLiveData = PlaybackViewModel.get(getApplication(), MEDIA_SOURCE_MODE_PLAYBACK)
                    .getPlaybackStateWrapper();
        }

        // A single observer simplifies the service (less risk to end up with multiple ones).
        mPlaybackLiveData.observe(this, mPlaybackStateListener);

        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_NONE);
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }

    private final Observer<PlaybackStateWrapper> mPlaybackStateListener =
            new Observer<PlaybackStateWrapper>() {
        @Override
        public void onChanged(PlaybackStateWrapper state) {
            if (mCurrentTask == null) {
                if (Log.isLoggable(TAG, Log.INFO)) {
                    Log.i(TAG, "No task, ignoring playback state");
                }
                return;
            }

            if (state == null) {
                if (Log.isLoggable(TAG, Log.INFO)) {
                    Log.i(TAG, "null state");
                }
                return;
            }

            // If the source to play was specified in the intent ignore others.
            ComponentName intentComp = mCurrentTask.mMediaComp;
            ComponentName stateComp = state.getMediaSource().getBrowseServiceComponentName();
            if (intentComp != null && !Objects.equals(stateComp, intentComp)) {
                if (Log.isLoggable(TAG, Log.INFO)) {
                    Log.i(TAG, "media sources don't match! stateComp: " + stateComp
                            + " intent intentComp: " + intentComp);
                }
                return;
            }

            // Listen to playback state updates to determine which actions are supported;
            // relevant actions here are prepare() and play()
            // If we should autoplay the source, we wait until play() is available before we
            // stop the service, otherwise just calling prepare() is sufficient.
            int startId = mCurrentTask.mStartId;
            boolean autoPlay = mCurrentTask.mAutoPlay;
            if (state.isPlaying()) {
                if (Log.isLoggable(TAG, Log.INFO)) {
                    Log.i(TAG, "Already playing");
                }
                stopTask();
                return;
            }
            MediaControllerCompat controller = state.getMediaController();
            if (controller == null) {
                Log.w(TAG, "controller is null");
                return;
            }
            MediaControllerCompat.TransportControls controls = controller.getTransportControls();
            if (controls == null) {
                Log.w(TAG, "controls is null");
                return;
            }

            long actions = state.getSupportedActions();
            if ((actions & PlaybackStateCompat.ACTION_PREPARE) != 0) {
                if (Log.isLoggable(TAG, Log.INFO)) {
                    Log.i(TAG, "prepare startId: " + startId + " AutoPlay: " + autoPlay);
                }
                controls.prepare();
                if (!autoPlay) {
                    stopTask();
                }
            }
            if (autoPlay && (actions & PlaybackStateCompat.ACTION_PLAY) != 0) {
                if (Log.isLoggable(TAG, Log.INFO)) {
                    Log.i(TAG, "play startId: " + startId);
                }
                controls.play();
                stopTask();
            }
        }
    };

    @Nullable
    private ComponentName getMediaComponentFromIntent(Intent intent) {
        String componentNameExtra = intent.getStringExtra(EXTRA_MEDIA_COMPONENT);
        if (TextUtils.isEmpty(componentNameExtra)) {
            Log.w(TAG, "EXTRA_MEDIA_COMPONENT not specified");
            return null;
        }

        ComponentName componentName = ComponentName.unflattenFromString(componentNameExtra);
        if (componentName == null) {
            Log.w(TAG, "Failed to un flatten: " + componentNameExtra);
            return null;
        }

        return componentName;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        boolean autoPlay = intent.getBooleanExtra(EXTRA_AUTOPLAY, false);
        ComponentName mediaComp = getMediaComponentFromIntent(intent);

        if (Log.isLoggable(TAG, Log.INFO)) {
            Log.i(TAG, "onStartCommand startId: " + startId + " autoPlay: " + autoPlay
                    + " mediaComp: " + mediaComp);
        }

        // Ignore the old value of mCurrentTask.  When it is non null, MediaConnectorService
        // is still trying to execute an old onStartCommand. However a newer call to onStartCommand
        // must take precedence, so just create and assign a new TaskInfo.
        mCurrentTask = new TaskInfo(startId, mediaComp, autoPlay);
        mPlaybackStateListener.onChanged(mPlaybackLiveData.getValue());

        // Since this service is started from CarMediaService (which runs in background), we need
        // to call startForeground to prevent the system from stopping this service and ANRing.
        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music)
                .setContentTitle(getResources().getString(R.string.service_notification_title))
                .build();
        startForeground(FOREGROUND_NOTIFICATION_ID, notification);
        return START_NOT_STICKY;
    }

    private void stopTask() {
        if (mCurrentTask != null) {
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "Stopping: " + mCurrentTask.mStartId);
            }
            stopSelf(mCurrentTask.mStartId);
            mCurrentTask = null;
        } else {
            Log.w(TAG, "Already stopped.");
        }
    }
}
