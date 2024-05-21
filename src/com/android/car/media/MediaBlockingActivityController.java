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

import static com.android.car.media.common.ui.PlaybackCardControllerUtilities.updateActionsWithPlaybackState;
import static com.android.car.media.common.ui.PlaybackCardControllerUtilities.updatePlayButtonWithPlaybackState;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.widget.TextView;

import androidx.constraintlayout.widget.Group;

import com.android.car.apps.common.util.ViewUtils;
import com.android.car.media.common.playback.PlaybackViewModel;
import com.android.car.media.common.ui.PlaybackCardController;

/**
 * Class used for managing view in the MediaBlockingActivity
 */
public class MediaBlockingActivityController extends PlaybackCardController {

    private static final String TAG = "MediaBlockingActivityController";
    private final Group mMediaViews;
    private final TextView mNoMediaView;
    private Drawable mSkipPreviousDrawable;
    private Drawable mSkipNextDrawable;
    private Drawable mActionItemBackgroundDrawable;

    /**
     * Builder for {@link NowPlayingController}. Overrides build() method to return
     * NowPlayingController rather than base {@link PlaybackCardController}
     */
    public static class Builder extends PlaybackCardController.Builder {

        @Override
        public MediaBlockingActivityController build() {
            MediaBlockingActivityController controller = new MediaBlockingActivityController(this);
            controller.setupController();
            return controller;
        }
    }

    public MediaBlockingActivityController(Builder builder) {
        super(builder);
        mMediaViews = mView.requireViewById(R.id.media_views_group);
        mNoMediaView = mView.requireViewById(R.id.no_media_text);
    }

    @Override
    protected void updatePlaybackState(PlaybackViewModel.PlaybackStateWrapper playbackState) {
        if (playbackState != null) {
            showViews(/* showMedia= */true);
            updatePlayButtonWithPlaybackState(mPlayPauseButton, playbackState);
            Context context = mView.getContext();

            if (mSkipPreviousDrawable == null) {
                mSkipPreviousDrawable = context.getDrawable(
                        com.android.car.media.common.R.drawable.ic_skip_previous);
            }
            if (mSkipNextDrawable == null) {
                mSkipNextDrawable = context.getDrawable(
                        com.android.car.media.common.R.drawable.ic_skip_next);
            }
            if (mActionItemBackgroundDrawable == null) {
                mActionItemBackgroundDrawable =
                        context.getDrawable(R.drawable.blocking_activity_action_item_background);
            }
            updateActionsWithPlaybackState(mView.getContext(), mActions, playbackState,
                    mDataModel.getPlaybackController().getValue(), mSkipPreviousDrawable,
                    mSkipNextDrawable, mActionItemBackgroundDrawable, mActionItemBackgroundDrawable,
                    false, null);
        } else {
            Log.d(TAG, "No PlaybackState found");
            showViews(/* showMedia= */ false);
        }
    }

    /**
     * Show or hide media UI
     */
    public void showViews(boolean showMedia) {
        ViewUtils.setVisible(mMediaViews, showMedia);
        ViewUtils.setVisible(mNoMediaView, !showMedia);
    }
}
