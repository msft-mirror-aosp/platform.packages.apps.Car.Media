/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.car.media.CarMediaManager.MEDIA_SOURCE_MODE_PLAYBACK;

import static androidx.media.utils.MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT;

import static com.android.car.media.common.MediaConstants.KEY_ROOT_HINT_MAX_QUEUE_ITEMS_WHILE_RESTRICTED;

import android.app.Application;
import android.os.Bundle;

import com.android.car.media.common.source.MediaBrowserConnector;
import com.android.car.media.common.source.MediaModels;
import com.android.car.media.widgets.AppBarController;


/** The application class. */
public class CarMediaApp extends Application {

    private MediaModels mMediaModelsPlayback = null;

    @Override
    public void onCreate() {
        super.onCreate();
        Bundle mediaSessionRootHints = new Bundle();
        int maxTabs = AppBarController.getMaxTabs(this);
        int maxQueue = NowPlayingController.getMaxItemsWhileRestricted(this);
        mediaSessionRootHints.putInt(BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT, maxTabs);
        mediaSessionRootHints.putInt(KEY_ROOT_HINT_MAX_QUEUE_ITEMS_WHILE_RESTRICTED, maxQueue);
        MediaBrowserConnector.addRootHints(mediaSessionRootHints);
    }

    /** Returns the {@link MediaModels} for the MEDIA_SOURCE_MODE_PLAYBACK. */
    public MediaModels getMediaModelsForPlaybackMode() {
        if (mMediaModelsPlayback == null) {
            mMediaModelsPlayback = new MediaModels(this, MEDIA_SOURCE_MODE_PLAYBACK);
        }
        return mMediaModelsPlayback;
    }
}
