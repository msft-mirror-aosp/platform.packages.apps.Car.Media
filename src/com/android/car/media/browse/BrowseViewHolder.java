/*
 * Copyright 2018 The Android Open Source Project
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
import android.text.TextUtils;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.android.car.apps.common.imaging.ImageBinder;
import com.android.car.apps.common.imaging.ImageViewBinder;
import com.android.car.apps.common.util.ViewUtils;
import com.android.car.media.MediaAppConfig;
import com.android.car.media.R;
import com.android.car.media.common.CustomBrowseAction;
import com.android.car.media.common.MediaItemMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generic {@link RecyclerView.ViewHolder} to use for all views in the {@link BrowseAdapter}
 */
public class BrowseViewHolder extends RecyclerView.ViewHolder {
    private final TextView mTitle;
    private final TextView mSubtitle;
    private final ImageView mAlbumArt;
    private final ViewGroup mContainer;
    private final ImageView mRightArrow;
    private final ImageView mTitleDownloadIcon;
    private final ImageView mTitleExplicitIcon;
    private final ImageView mSubTitleDownloadIcon;
    private final ImageView mSubTitleExplicitIcon;
    private final ProgressBar mProgressbar;
    private final ImageView mNewMediaDot;
    private final ViewGroup mCustomActionsContainer;

    private final Size mMaxArtSize;
    private final ImageViewBinder<MediaItemMetadata.ArtworkRef> mAlbumArtBinder;
    private final List<ImageViewBinder<CustomBrowseAction.BrowseActionArtRef>>
            mBrowseActionIcons;

    /**
     * Creates a {@link BrowseViewHolder} for the given view.
     */
    BrowseViewHolder(View itemView, ImageBinder.PlaceholderType placeholderType) {
        super(itemView);
        mTitle = itemView.findViewById(com.android.car.media.R.id.title);
        mSubtitle = itemView.findViewById(com.android.car.media.R.id.subtitle);
        mAlbumArt = itemView.findViewById(com.android.car.media.R.id.thumbnail);
        mContainer = itemView.findViewById(com.android.car.media.R.id.item_container);
        mRightArrow = itemView.findViewById(com.android.car.media.R.id.right_arrow);
        mTitleDownloadIcon = itemView.findViewById(
                com.android.car.media.R.id.download_icon_with_title);
        mTitleExplicitIcon = itemView.findViewById(
                com.android.car.media.R.id.explicit_icon_with_title);
        mSubTitleDownloadIcon = itemView.findViewById(
                com.android.car.media.R.id.download_icon_with_subtitle);
        mSubTitleExplicitIcon = itemView.findViewById(
                com.android.car.media.R.id.explicit_icon_with_subtitle);
        mProgressbar = itemView.findViewById(com.android.car.media.R.id.browse_item_progress_bar);
        mNewMediaDot = itemView.findViewById(com.android.car.media.R.id.browse_item_progress_new);

        mMaxArtSize = MediaAppConfig.getMediaItemsBitmapMaxSize(itemView.getContext());
        mAlbumArtBinder = new ImageViewBinder<>(placeholderType, mMaxArtSize, mAlbumArt, false);
        mCustomActionsContainer =
                itemView.findViewById(com.android.car.media.R.id.browse_item_actions_container);
        mBrowseActionIcons = new ArrayList<>();
    }

    /**
     * Updates this {@link BrowseViewHolder} with the given data
     */
    public void bind(Context context, BrowseViewData data) {

        MediaItemMetadata metadata = data.mMediaItem;
        boolean hasMediaItem = metadata != null;
        boolean hasMediaItemExtras = hasMediaItem && metadata.getExtras() != null;
        boolean showSubtitle = hasMediaItem && !TextUtils.isEmpty(metadata.getSubtitle());
        boolean hasBrowseCustomActions = !data.mCustomBrowseActions.isEmpty();

        if (mTitle != null) {
            mTitle.setText(data.mText != null ? data.mText :
                    hasMediaItem ? metadata.getTitle() : null);
        }
        if (mSubtitle != null) {
            mSubtitle.setText(hasMediaItem ? metadata.getSubtitle() : null);
            ViewUtils.setVisible(mSubtitle, showSubtitle);
        }

        mAlbumArtBinder.setImage(context, hasMediaItem ? metadata.getArtworkKey() : null);

        if (mContainer != null && data.mCallback != null) {
            mContainer.setOnClickListener(v -> data.mCallback.onMediaItemClicked(data));
        }
        ViewUtils.setVisible(mRightArrow, hasMediaItem && metadata.isBrowsable());

        // Adjust the positioning of the explicit and downloaded icons. If there is a subtitle, then
        // the icons should show on the subtitle row, otherwise they should show on the title row.
        boolean downloaded = hasMediaItem && metadata.isDownloaded();
        boolean explicit = hasMediaItem && metadata.isExplicit();
        ViewUtils.setVisible(mTitleDownloadIcon, !showSubtitle && downloaded);
        ViewUtils.setVisible(mTitleExplicitIcon, !showSubtitle && explicit);
        ViewUtils.setVisible(mSubTitleDownloadIcon, showSubtitle && downloaded);
        ViewUtils.setVisible(mSubTitleExplicitIcon, showSubtitle && explicit);

        if (hasMediaItemExtras) {
            bindProgressUI(metadata);
        }

        if (hasBrowseCustomActions && !metadata.isBrowsable()) {
            ViewUtils.setVisible(mCustomActionsContainer, true);
            bindBrowseCustomActions(context, data);
        } else {
            ViewUtils.setVisible(mCustomActionsContainer, false);
        }
    }

    /**
     * Handles updated {@link BrowseViewData} for a partial bind Partial bind is {@link
     * androidx.recyclerview.widget.ListAdapter#onBindViewHolder(RecyclerView.ViewHolder, int,
     * List)}
     *
     * <p>Called from {@link DiffUtil.ItemCallback#getChangePayload(Object, Object)} where items
     * same but contents were different and create payload for partial bind
     *
     * <p>Or called from {@link
     * androidx.recyclerview.widget.RecyclerView.AdapterDataObserver#onItemRangeChanged(int, int,
     * Object)} Where we check if notifyItemChanged() has a payload or not and then call a partial
     * bind
     *
     * <p>
     */
    public void update(
            BrowseViewData browseViewData, BrowseAdapter.MediaItemUpdateType updateType) {
        if (updateType == null) {
            bind(itemView.getContext(), browseViewData);
            return;
        }
        switch (updateType) {
            case PROGRESS:
                bindProgressUI(browseViewData.mMediaItem);
                break;
            case BROWSE_ACTIONS:
                Context context = itemView.getContext();
                bindBrowseCustomActions(context, browseViewData);
                break;
            default:
                bind(itemView.getContext(), browseViewData);
                break;
        }
    }

    /**
     * Binds UI for playback progress and new media indicator
     * @param mediaItemMetadata
     */
    private void bindProgressUI(MediaItemMetadata mediaItemMetadata) {
        int playbackStatus = mediaItemMetadata.getPlaybackStatus();
        BrowseAdapterUtils.handleNewMediaIndicator(playbackStatus, mNewMediaDot);
        double progress = mediaItemMetadata.getProgress();
        BrowseAdapterUtils.setPlaybackProgressIndicator(mProgressbar, progress);
    }

    private void bindBrowseCustomActions(Context context, BrowseViewData browseViewData) {
        mCustomActionsContainer.removeAllViews();
        mBrowseActionIcons.forEach((it) -> it.maybeCancelLoading(context));
        mBrowseActionIcons.clear();

        int maxVisibleActions = context.getResources().getInteger(R.integer.max_visible_actions);
        int numActions = browseViewData.mCustomBrowseActions.size();
        boolean willOverflow = numActions > maxVisibleActions;
        int actionsToShow = willOverflow ? Math.max(0, maxVisibleActions - 1) : maxVisibleActions;

        for (CustomBrowseAction customBrowseAction :
                        browseViewData.mCustomBrowseActions.stream()
                            .limit(actionsToShow)
                            .collect(Collectors.toList())) {
            View customActionView =
                    LayoutInflater.from(context).inflate(R.layout.browse_custom_action, null);
            customActionView.setOnClickListener(
                    (v) ->
                    browseViewData.mCallback.onBrowseActionClick(
                        customBrowseAction, browseViewData));
            ImageView imageView = customActionView.findViewById(R.id.browse_item_custom_action);
            ImageViewBinder<CustomBrowseAction.BrowseActionArtRef> viewBinder =
                    new ImageViewBinder(mMaxArtSize, imageView);
            viewBinder.setImage(context, customBrowseAction.getArtRef());
            mBrowseActionIcons.add(viewBinder);
            mCustomActionsContainer.addView(customActionView);
        }

        if (willOverflow) {
            View customActionView =
                    LayoutInflater.from(context)
                    .inflate(R.layout.browse_custom_action, null);
            customActionView.setOnClickListener(v -> browseViewData.mCallback.onOverflowClicked(
                    browseViewData.mCustomBrowseActions.subList(actionsToShow, numActions),
                    browseViewData));
            ImageView imageView =
                    customActionView.findViewById(R.id.browse_item_custom_action);
            imageView.setImageResource(R.drawable.car_ui_icon_overflow_menu);
            mCustomActionsContainer.addView(customActionView);
        }
    }

    void onViewAttachedToWindow(Context context) {
        mAlbumArtBinder.maybeRestartLoading(context);
        mBrowseActionIcons.forEach((it) -> it.maybeRestartLoading(context));
    }

    void onViewDetachedFromWindow(Context context) {
        mAlbumArtBinder.maybeCancelLoading(context);
        mBrowseActionIcons.forEach((it) -> it.maybeCancelLoading(context));
    }
}
