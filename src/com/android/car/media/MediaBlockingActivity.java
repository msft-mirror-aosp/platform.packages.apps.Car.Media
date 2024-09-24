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
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.apps.common.util.IntentUtils;
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

    private MediaModels mMediaModels;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.media_blocking_activity);

        Intent intent = getIntent();
        int exitButtonVisibility = intent.getIntExtra(
                IntentUtils.EXTRA_MEDIA_BLOCKING_ACTIVITY_EXIT_BUTTON_VISIBILITY, View.VISIBLE);
        // Ensure the visibility value is valid
        if (exitButtonVisibility != View.VISIBLE && exitButtonVisibility != View.INVISIBLE
                && exitButtonVisibility != View.GONE) {
            exitButtonVisibility = View.VISIBLE;
        }

        if (!intent.hasExtra(Intent.EXTRA_COMPONENT_NAME)) {
            Log.i(TAG, "Caller must provide valid media activity extra");
            setupController(/* mediaSource= */ null, exitButtonVisibility);
            return;
        }
        String targetMediaApp = intent.getStringExtra(Intent.EXTRA_COMPONENT_NAME);
        ComponentName componentName = ComponentName.unflattenFromString(targetMediaApp);
        if (componentName == null) {
            Log.i(TAG, "Caller must provide valid media activity extra");
            setupController(/* mediaSource= */ null, exitButtonVisibility);
            return;
        }
        MediaSource mediaSource = findMediaSource(componentName);
        if (mediaSource == null) {
            Log.i(TAG, "Unable to find media session associated with " + componentName);
        }

        setupController(mediaSource, exitButtonVisibility);
    }

    @Override
    protected void onStop() {
        super.onStop();

        // MediaBlockingActivity is only meant to be a foreground activity.
        if (!isFinishing()) {
            Log.d(TAG, "User navigated away, calling finish()");
            finish();
        }

        cleanup();
    }

    @Override
    public void onBackPressed() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);

        finish();
    }

    /**
     * Finds media source that matches the component name passed in from the activity intent
     */
    private MediaSource findMediaSource(ComponentName componentName) {
        MediaSessionHelper mediaSessionHelper = MediaSessionHelper.getInstance(this);
        List<MediaSource> mediaSources =
                mediaSessionHelper.getActiveOrPausedMediaSources().getValue();

        for (MediaSource mediaSource : mediaSources) {
            if (mediaSource.getPackageName().equals(componentName.getPackageName())) {
                return mediaSource;
            }
        }

        return null;
    }

    private void setupController(MediaSource mediaSource, int exitButtonVisibility) {
        ViewGroup mRootView = requireViewById(R.id.media_blocking_activity_root);

        mMediaModels = new MediaModels(this, mediaSource);
        PlaybackCardViewModel viewModel =
                new ViewModelProvider(this).get(PlaybackCardViewModel.class);
        if (viewModel.needsInitialization()) {
            viewModel.init(mMediaModels);
        }
        MediaBlockingActivityController controller =
                (MediaBlockingActivityController) new MediaBlockingActivityController.Builder()
                    .setExitButtonVisibility(exitButtonVisibility)
                    .setExitButtonOnClick(view -> launchActivityAndFinish(mediaSource))
                    // Unable to get MediaSource information, assume a crash and recompute ABA
                    .setNullPlaybackStateListener(() -> finish())
                    .setModels(mMediaModels.getPlaybackViewModel(), viewModel,
                            mMediaModels.getMediaItemsRepository())
                    .setViewGroup(mRootView)
                    .build();

        if (mediaSource == null) {
            controller.showFallbackView(/* showFallbackView= */ true);
        }
    }

    private void launchActivityAndFinish(MediaSource mediaSource) {
        if (mediaSource != null) {
            // Relaunch blocked app in case it crashed or isn't behind the blocking activity anymore
            new Handler(getMainLooper()).postDelayed(() ->
                    startActivity(mediaSource.getIntent()),
                    R.integer.blocking_activity_relaunch_time_ms);
        }

        finish();
    }

    private void cleanup() {
        mMediaModels.onCleared();
    }
}
