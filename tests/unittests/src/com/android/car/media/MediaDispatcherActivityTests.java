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

import static android.car.media.CarMediaIntents.EXTRA_MEDIA_COMPONENT;

import static com.android.car.media.service.MediaConnectorServiceTests.newFakeMediaSource;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.media.CarMediaIntents;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.media.common.source.CarMediaManagerHelper;
import com.android.car.media.common.source.MediaSource;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;

@RunWith(AndroidJUnit4.class)
public class MediaDispatcherActivityTests extends BaseMockitoTest {

    @Mock Context mContext;
    @Mock Resources mResources;
    @Mock PackageManager mPackageManager;
    @Mock CarMediaManagerHelper mCarMMH;

    @Captor
    ArgumentCaptor<Intent> mIntentCaptor;

    @Before
    public void setUp() {
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
    }

    @Test
    public void customMediaAppIntent() {
        ComponentName customComp = new ComponentName("package", "class");
        String flatComp = customComp.flattenToString();

        String[] customApps = { flatComp };
        when(mResources.getStringArray(anyInt())).thenReturn(customApps);
        when(mPackageManager.getLaunchIntentForPackage(any())).thenReturn(new Intent("LaunchMe"));

        Intent newIntent = new Intent(CarMediaIntents.ACTION_MEDIA_TEMPLATE);
        newIntent.putExtra(CarMediaIntents.EXTRA_MEDIA_COMPONENT, flatComp);

        try (MockedStatic<MediaSource> mockedStatic = mockStatic(MediaSource.class)) {
            mockedStatic.when(() -> MediaSource.create(any(), any(ComponentName.class)))
                    .thenAnswer((Answer<MediaSource>) invocation -> {
                        ComponentName compArg = invocation.getArgument(1);
                        return newFakeMediaSource(mContext, compArg);
                    });

            MediaDispatcherActivity.dispatch(mContext, newIntent, mCarMMH);
        }

        verify(mContext, times(1)).startActivity(mIntentCaptor.capture());
        assertEquals(flatComp, mIntentCaptor.getValue().getStringExtra(EXTRA_MEDIA_COMPONENT));
    }

}
