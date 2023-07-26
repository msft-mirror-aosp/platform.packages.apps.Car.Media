/*
 * Copyright 2023 The Android Open Source Project
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

import static android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY;
import static android.support.v4.media.session.PlaybackStateCompat.ACTION_PREPARE;

import static com.android.car.apps.common.util.LiveDataFunctions.dataOf;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.media.CarMediaIntents;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.MutableLiveData;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.apps.common.IconCropper;
import com.android.car.media.common.playback.PlaybackViewModel.PlaybackStateWrapper;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.service.MediaConnectorService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;


@RunWith(AndroidJUnit4.class)
public class MediaConnectorServiceTests extends BaseMockitoTest {

    private static final String EXTRA_AUTOPLAY = "com.android.car.media.autoplay";

    private static final ComponentName COMP_1_1 = new ComponentName("package1", "class1");
    private static final ComponentName COMP_1_2 = new ComponentName("package1", "class2");

    private MutableLiveData<PlaybackStateWrapper> mPlaybackLiveData;
    private MediaConnectorService mService;

    @Mock private MediaControllerCompat mMediaController;
    @Mock private MediaMetadataCompat mMetadata;
    @Mock private PlaybackStateCompat mState;
    @Mock MediaControllerCompat.TransportControls mControls;

    @Before
    public void setup() {
        mPlaybackLiveData = dataOf(null);
        mService = new MediaConnectorService(mPlaybackLiveData);
        mService.attachBaseContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
        mService.onCreate();
        mService.onBind(new Intent());

        when(mMediaController.getTransportControls()).thenReturn(mControls);
    }

    public static MediaSource newFakeMediaSource(@NonNull ComponentName browseService) {
        String displayName = browseService.getClassName();
        Drawable icon = new ColorDrawable();
        IconCropper iconCropper = new IconCropper(new Path());
        return new MediaSource(browseService, displayName, icon, iconCropper);
    }

    private void sendCommand(@Nullable ComponentName source, boolean autoPlay) {
        Intent intent = new Intent();
        intent.putExtra(EXTRA_AUTOPLAY, autoPlay);
        if (source != null) {
            intent.putExtra(CarMediaIntents.EXTRA_MEDIA_COMPONENT, source.flattenToString());
        }
        mService.onStartCommand(intent, 0, 0);
    }

    private PlaybackStateWrapper newState(ComponentName comp) {
        MediaSource source = newFakeMediaSource(comp);
        return new PlaybackStateWrapper(source, mMediaController, mMetadata, mState);
    }

    @Test
    public void prepareAndPlaySequence() {
        when(mState.getActions()).thenReturn(0L, ACTION_PREPARE, ACTION_PLAY);
        sendCommand(COMP_1_1, true);

        PlaybackStateWrapper state = newState(COMP_1_1);

        mPlaybackLiveData.setValue(state);
        verify(mControls, times(0)).prepare();
        verify(mControls, times(0)).play();

        mPlaybackLiveData.setValue(state);
        verify(mControls, times(1)).prepare();
        verify(mControls, times(0)).play();

        mPlaybackLiveData.setValue(state);
        verify(mControls, times(1)).prepare();
        verify(mControls, times(1)).play();
    }

    @Test
    public void onlyPrepareWhenPlayNotEnabled() {
        when(mState.getActions()).thenReturn(ACTION_PREPARE);
        sendCommand(COMP_1_1, true);
        mPlaybackLiveData.setValue(newState(COMP_1_1));
        verify(mControls, times(1)).prepare();
        verify(mControls, times(0)).play();
    }

    @Test
    public void onlyPrepareWhenPlayNotRequested() {
        when(mState.getActions()).thenReturn(ACTION_PREPARE | ACTION_PLAY);
        sendCommand(COMP_1_1, false);
        mPlaybackLiveData.setValue(newState(COMP_1_1));
        verify(mControls, times(1)).prepare();
        verify(mControls, times(0)).play();
    }

    @Test
    public void waitForMatchingSource() {
        when(mState.getActions()).thenReturn(ACTION_PREPARE | ACTION_PLAY);
        sendCommand(COMP_1_2, true);

        mPlaybackLiveData.setValue(newState(COMP_1_1));
        verify(mControls, times(0)).prepare();
        verify(mControls, times(0)).play();

        mPlaybackLiveData.setValue(newState(COMP_1_2));
        verify(mControls, times(1)).prepare();
        verify(mControls, times(1)).play();
    }
}
