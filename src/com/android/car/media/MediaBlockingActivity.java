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

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.media.common.source.MediaModels;
import com.android.car.media.common.source.MediaSessionHelper;
import com.android.car.media.common.source.MediaSource;
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

        Intent intent = getIntent();
        if (!intent.hasExtra(Intent.EXTRA_COMPONENT_NAME)) {
            Log.i(TAG, "Caller must provide valid media activity extra");
            setupController(/* mediaSource= */ null);
            return;
        }
        String targetMediaApp = intent.getStringExtra(Intent.EXTRA_COMPONENT_NAME);
        ComponentName componentName = ComponentName.unflattenFromString(targetMediaApp);
        if (componentName == null) {
            Log.i(TAG, "Caller must provide valid media activity extra");
            setupController(/* mediaSource= */ null);
            return;
        }
        MediaSource mediaSource = findMediaSource(componentName);
        if (mediaSource == null) {
            Log.i(TAG, "Unable to find media session associated with " + componentName);
        }

        setupController(mediaSource);
    }

    @Override
    protected void onStop() {
        super.onStop();

        // As a blocking activity, MediaBlockingActivity is only meant to be a foreground activity.
        if (!isFinishing()) {
            Log.d(TAG, "User navigated away, calling finish()");
            finish();
        }
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

    private void setupController(MediaSource mediaSource) {
        ViewGroup mRootView = requireViewById(R.id.media_blocking_activity_root);

        MediaModels mediaModels = new MediaModels(this, mediaSource);
        PlaybackCardViewModel viewModel =
                new ViewModelProvider(this).get(PlaybackCardViewModel.class);
        if (viewModel.needsInitialization()) {
            viewModel.init(mediaModels);
        }
        MediaBlockingActivityController controller =
                (MediaBlockingActivityController) new MediaBlockingActivityController.Builder()
                        .setExitButtonOnClick(view -> finish())
                        .setModels(mediaModels.getPlaybackViewModel(), viewModel,
                                mediaModels.getMediaItemsRepository())
                        .setViewGroup(mRootView)
                        .build();

        if (mediaSource == null) {
            controller.showViews(/* showMedia= */ false);
        }
    }
}
