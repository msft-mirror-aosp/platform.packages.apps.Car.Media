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

import android.content.Context;
import android.util.AttributeSet;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;

import com.android.car.apps.common.BackgroundImageView;
import com.android.car.apps.common.imaging.ImageBinder;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.MetadataController;
import com.android.car.media.common.playback.PlaybackViewModel;


/**
 * This is a CarControlBar used for displaying Media content, including metadata for the currently
 * playing song and basic controls.
 */
public class BrowseMiniMediaItemView extends BrowseMiniMediaItemBar {

    private static final String TAG = "Media.ControlBar";

    private MetadataController mMetadataController;
    private ImageBinder<MediaItemMetadata.ArtworkRef> mArtBinder = null;
    private PlaybackViewModel mPlaybackViewModel;


    public BrowseMiniMediaItemView(Context context) {
        this(context, null);
    }

    public BrowseMiniMediaItemView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BrowseMiniMediaItemView(Context context, AttributeSet attrs, int defStyleAttrs) {
        super(context, attrs, defStyleAttrs, com.android.car.media.R.layout.browse_mini_bar_view);
        BackgroundImageView artBackground =
                findViewById(com.android.car.media.common.R.id.art_background);
        if (artBackground != null) {
            int max = getResources()
                    .getInteger(
                            com.android.car.media.common.R.integer.media_items_bitmap_max_size_px);
            Size maxArtSize = new Size(max, max);
            mArtBinder = new ImageBinder<>(ImageBinder.PlaceholderType.BACKGROUND, maxArtSize,
                    artBackground::setBackgroundDrawable);
        }
    }

    /** Connects the bar to the {@link PlaybackViewModel}. */
    public void setModel(@NonNull PlaybackViewModel model, @NonNull LifecycleOwner owner,
                         @NonNull Size maxArtSize) {
        mMetadataController = new MetadataController(owner, model, null, mTitle, mSubtitle, null,
                null, null, null, null, null, mContentTile, mAppIcon, maxArtSize, null, null);
        mPlaybackViewModel = model;

        if (mArtBinder != null) {
            mPlaybackViewModel.getMetadata().observe(owner,
                    item -> mArtBinder.setImage(getContext(),
                            item != null ? item.getArtworkKey() : null));
        }
    }
}
