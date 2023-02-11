/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.car.media.browse;

import android.os.Bundle;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.media.utils.MediaConstants;

import com.android.car.media.common.MediaItemMetadata;

import java.util.ArrayList;
import java.util.List;

public class BrowseTestUtils {
    public static MediaItemMetadata generateParentItem() {
        MediaDescriptionCompat.Builder builder = new MediaDescriptionCompat.Builder();

        builder.setTitle("ParentItem1")
                .setSubtitle("Parent")
                .setDescription("Parent item desc")
                .setMediaId("ParentItem");
        return new MediaItemMetadata(builder.build(), 0L, true, false, null);
    }

    public static List<MediaItemMetadata> generateTestItems() {
        MediaDescriptionCompat.Builder builder = new MediaDescriptionCompat.Builder();
        builder.setTitle("TestItem1")
                .setSubtitle("Tester1")
                .setDescription("Test item 1 desc")
                .setMediaId("TestItem1");
        MediaItemMetadata browsableItem = new MediaItemMetadata(builder.build(), 1L, true, false,
                null);

        builder.setTitle("TestItem2")
                .setSubtitle("Tester2")
                .setDescription("Test item 2 desc")
                .setMediaId("TestItem2");
        MediaItemMetadata playableItem = new MediaItemMetadata(builder.build(), 2L, false, true,
                null);


        Bundle extras = new Bundle();
        extras.putLong("android.media.extra.DOWNLOAD_STATUS", 2L);
        extras.putLong("android.media.IS_EXPLICIT", 1L);
        extras.putInt("android.media.extra.PLAYBACK_STATUS",
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED);
        extras.putDouble("androidx.media.MediaItem.Extras.COMPLETION_PERCENTAGE", .33);
        builder.setTitle("TestItem3")
                .setSubtitle(null)
                .setDescription("Test item 3 desc")
                .setMediaId("TestItem3")
                .setExtras(extras);
        MediaItemMetadata downloadedExplicitNewProgress = new MediaItemMetadata(builder.build(), 3L,
                false, true, null);

        Bundle newExtras = new Bundle();
        newExtras.putLong("android.media.extra.DOWNLOAD_STATUS", 2L);
        newExtras.putLong("android.media.IS_EXPLICIT", 1L);
        newExtras.putInt("android.media.extra.PLAYBACK_STATUS",
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED);
        newExtras.putDouble("androidx.media.MediaItem.Extras.COMPLETION_PERCENTAGE", .66);
        builder.setTitle("TestItem3")
                .setSubtitle(null)
                .setDescription("Test item 3 desc")
                .setMediaId("TestItem3")
                .setExtras(newExtras);
        MediaItemMetadata downloadedExplicitNewProgress2 = new MediaItemMetadata(builder.build(),
                3L, false, true, null);

        Bundle finishedPlayExtras = new Bundle();
        finishedPlayExtras.putLong("android.media.extra.DOWNLOAD_STATUS", 2L);
        finishedPlayExtras.putLong("android.media.IS_EXPLICIT", 1L);
        finishedPlayExtras.putInt("android.media.extra.PLAYBACK_STATUS",
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED);
        finishedPlayExtras.putDouble("androidx.media.MediaItem.Extras.COMPLETION_PERCENTAGE", 1.0);
        builder.setTitle("TestItem3")
                .setSubtitle(null)
                .setDescription("Test item 3 desc")
                .setMediaId("TestItem3")
                .setExtras(finishedPlayExtras);
        MediaItemMetadata downloadedExplicitNewProgress3 = new MediaItemMetadata(builder.build(),
                3L, false, true, null);

        List<MediaItemMetadata> itemList = new ArrayList<>();
        itemList.add(browsableItem);
        itemList.add(playableItem);
        itemList.add(downloadedExplicitNewProgress);
        itemList.add(downloadedExplicitNewProgress2);
        itemList.add(downloadedExplicitNewProgress3);

        return itemList;
    }
}
