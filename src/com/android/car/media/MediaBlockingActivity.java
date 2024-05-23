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

import static android.car.drivingstate.CarDrivingStateEvent.DRIVING_STATE_PARKED;

import android.car.Car;
import android.car.drivingstate.CarDrivingStateManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
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

    private Car mCar;
    private CarDrivingStateManager mCarDrivingStateManager;

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

        boolean shouldDismissOnPark  = intent.getBooleanExtra(
                IntentUtils.EXTRA_MEDIA_BLOCKING_ACTIVITY_DISMISS_ON_PARK, true);

        if (shouldDismissOnPark) {
            Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                    (car, ready) -> {
                        if (!ready) {
                            cleanupCarManagers();
                        } else {
                            mCar = car;
                            mCarDrivingStateManager =
                                    mCar.getCarManager(CarDrivingStateManager.class);
                            if (mCarDrivingStateManager.getCurrentCarDrivingState().eventValue
                                    == DRIVING_STATE_PARKED) {
                                launchActivityAndFinish(mediaSource);
                            }
                            mCarDrivingStateManager.registerListener(
                                    carDrivingStateEvent -> {
                                        if (carDrivingStateEvent.eventValue
                                                == DRIVING_STATE_PARKED) {
                                            launchActivityAndFinish(mediaSource);
                                        }
                                    });
                        }
                    });
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        // MediaBlockingActivity is only meant to be a foreground activity.
        if (!isFinishing()) {
            Log.d(TAG, "User navigated away, calling finish()");
            finish();
        }

        cleanupCarManagers();
    }

    /**
     * Finds media source that matches the component name passed in from the activity intent
     */
    private MediaSource findMediaSource(ComponentName componentName) {
        MediaSessionHelper mediaSessionHelper = MediaSessionHelper.getInstance(this);
        List<MediaSource> mediaSources = mediaSessionHelper.getActiveMediaSources().getValue();

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
                        .setModels(mediaModels.getPlaybackViewModel(), viewModel,
                                mediaModels.getMediaItemsRepository())
                        .setViewGroup(mRootView)
                        .build();

        if (mediaSource == null) {
            controller.showViews(/* showMedia= */ false);
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

    private void cleanupCarManagers() {
        if (mCarDrivingStateManager != null) {
            mCarDrivingStateManager.unregisterListener();
            mCarDrivingStateManager = null;
        }

        if (mCar != null) {
            mCar.disconnect();
            mCar = null;
        }
    }
}
