/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.car.media;

import android.car.media.CarMediaIntents;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.Group;

import com.android.car.apps.common.UxrButton;
import com.android.car.media.common.source.MediaModels;
import com.android.car.media.common.source.MediaSessionHelper;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.ui.PlaybackCardController;
import com.android.car.media.common.ui.PlaybackCardViewModel;

import java.util.List;

/**
 * Activity for handling blocking ui of background audio non-mbs NDO apps while driving.
 * Launched from the system when there is a foreground NDO app with a non-mbs media session.
 * <p>
 * MediaBlockingActivity displays media controls and does not get dismissed when the car changes
 * into park. Instead, the user must manually click the exit button or navigate away.
 */
public class MediaBlockingActivity extends AppCompatActivity {

    private static final String TAG = "MediaBlockingActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.media_blocking_activity);
        ViewGroup mRootView = requireViewById(R.id.media_blocking_activity_root);

        Intent intent = getIntent();
        if (!intent.hasExtra(CarMediaIntents.EXTRA_MEDIA_COMPONENT)) {
            Log.i(TAG, "Caller must provide valid media activity extra");
            setupNoMediaView();
            return;
        }
        String targetMediaApp = intent.getStringExtra(CarMediaIntents.EXTRA_MEDIA_COMPONENT);
        ComponentName componentName = ComponentName.unflattenFromString(targetMediaApp);
        if (componentName == null) {
            Log.i(TAG, "Caller must provide valid media activity extra");
            setupNoMediaView();
            return;
        }
        MediaSource mediaSource = findMediaSource(componentName);
        if (mediaSource == null) {
            Log.i(TAG, "Unable to find media session associated with "
                    + componentName);
            setupNoMediaView();
            return;
        }

        MediaModels mediaModels = new MediaModels(this, mediaSource);
        PlaybackCardViewModel playbackCardViewModel = new PlaybackCardViewModel(getApplication());
        new PlaybackCardController.Builder()
            .setModels(mediaModels.getPlaybackViewModel(), playbackCardViewModel,
                mediaModels.getMediaItemsRepository())
            .setViewGroup(mRootView)
            .build();

        setupExitButton();
    }

    @Override
    protected void onStop() {
        super.onStop();

        // As a blocking activity, MediaBlockingActivity is only meant to be a foreground activity.
        if (isFinishing()) {
            Log.d(TAG, "User navigated away, calling finish()");
            finish();
        }
    }

    private void setupNoMediaView() {
        // Unable to find a valid media session, fall back to blocking text
        Group mediaViews = requireViewById(R.id.media_views_group);
        TextView noMediaTextView = requireViewById(R.id.no_media_text);

        mediaViews.setVisibility(View.GONE);
        noMediaTextView.setVisibility(View.VISIBLE);
    }

    private MediaSource findMediaSource(ComponentName componentName) {
        MediaSessionHelper mediaSessionHelper = MediaSessionHelper.getInstance(this);
        List<MediaSource> mediaSources = mediaSessionHelper.getPlayableMediaSources().getValue();

        for (MediaSource mediaSource : mediaSources) {
            if (mediaSource.getPackageName().equals(componentName.getPackageName())) {
                return mediaSource;
            }
        }

        return null;
    }

    private void setupExitButton() {
        UxrButton exitButton = requireViewById(R.id.exit_button);
        exitButton.setOnClickListener(view -> finish());
    }
}
