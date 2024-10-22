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

import static java.util.Collections.emptyList;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
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
import com.android.car.media.common.ui.PlaybackCardControllerUtilities;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generic {@link RecyclerView.ViewHolder} to use for all views in the {@link BrowseAdapter}
 */
public class BrowseViewHolder extends RecyclerView.ViewHolder {

    private static final String TAG = "BrowseViewHolder";

    protected final TextView mTitle;
    protected final TextView mSubtitle;
    protected final ImageView mAlbumArt;
    protected final View mMediaItemClickTarget;
    protected final ImageView mRightArrow;
    protected final ImageView mTitleDownloadIcon;
    protected final ImageView mTitleExplicitIcon;
    protected final ImageView mSubTitleDownloadIcon;
    protected final ImageView mSubTitleExplicitIcon;
    protected final ProgressBar mProgressbar;
    protected final ImageView mNewMediaDot;
    protected final ViewGroup mCustomActionsContainer;
    protected final ViewGroup mIndicatorIconsContainer;
    protected final int mMaxIndicatorIcons;

    protected final Size mMaxArtSize;
    protected final ImageViewBinder<MediaItemMetadata.ArtworkRef> mAlbumArtBinder;
    protected final List<ImageViewBinder<ImageBinder.ImageRef>> mBrowseActionIcons;

    /**
     * Produces BrowseViewHolder instances. An OEM extension can be used by overlaying
     * R.string.config_BrowseViewHolderFactory_className.
     */
    public static class Factory {

        /** Default constructor */
        public Factory() {}

        /** Creates a new {@link BrowseViewHolder} instance. */
        public BrowseViewHolder create(View itemView, ImageBinder.PlaceholderType placeholderType) {
            return new BrowseViewHolder(itemView, placeholderType);
        }
    }

    /**
     * Creates a {@link BrowseViewHolder} for the given view.
     */
    public BrowseViewHolder(View itemView, ImageBinder.PlaceholderType placeholderType) {
        super(itemView);
        mTitle = itemView.findViewById(com.android.car.media.R.id.title);
        mSubtitle = itemView.findViewById(com.android.car.media.R.id.subtitle);
        mAlbumArt = itemView.findViewById(com.android.car.media.R.id.thumbnail);

        // Due to RROs, changing the id was not possible => prefer the new but fallback to the old.
        View ct = itemView.findViewById(com.android.car.media.R.id.media_item_click_target);
        if (ct == null) {
            ct = itemView.findViewById(com.android.car.media.R.id.item_container);
        }
        mMediaItemClickTarget = ct;
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
        mIndicatorIconsContainer =
                itemView.findViewById(com.android.car.media.R.id.indicator_icons_container);
        mMaxIndicatorIcons = itemView.getResources().getInteger(
                com.android.car.media.common.R.integer.max_indicator_icons_per_media_item);
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
        List<Uri> indicatorUris = hasMediaItem ? metadata.getSmallIconsUriList() : emptyList();
        boolean hasIndicators = indicatorUris.size() > 0;

        if (mTitle != null) {
            mTitle.setText(data.mText != null ? data.mText :
                    hasMediaItem ? metadata.getTitle() : null);
        }
        if (mSubtitle != null) {
            mSubtitle.setText(hasMediaItem ? metadata.getSubtitle() : null);
            ViewUtils.setVisible(mSubtitle, showSubtitle || hasIndicators);
        }

        mAlbumArtBinder.setImage(context, hasMediaItem ? metadata.getArtworkKey() : null);

        if (mMediaItemClickTarget != null && data.mCallback != null) {
            mMediaItemClickTarget.setOnClickListener(v -> data.mCallback.onMediaItemClicked(data));
        }
        ViewUtils.setVisible(mRightArrow, hasMediaItem && metadata.isBrowsable());

        // Adjust the positioning of the explicit and downloaded icons. If there is a subtitle, then
        // the icons should show on the subtitle row, otherwise they should show on the title row.
        // If any indicator icons are sent, they are supposed to include explicit and downloaded
        // icons (to avoid style mismatch), so metadata.isDownloaded() and metadata.isExplicit()
        // must be be ignored.
        boolean downloaded = !hasIndicators && hasMediaItem && metadata.isDownloaded();
        boolean explicit = !hasIndicators && hasMediaItem && metadata.isExplicit();
        ViewUtils.setVisible(mTitleDownloadIcon, !showSubtitle && downloaded);
        ViewUtils.setVisible(mTitleExplicitIcon, !showSubtitle && explicit);
        ViewUtils.setVisible(mSubTitleDownloadIcon, showSubtitle && downloaded);
        ViewUtils.setVisible(mSubTitleExplicitIcon, showSubtitle && explicit);

        if (hasMediaItem) {
            PlaybackCardControllerUtilities.bindIndicatorIcons(mIndicatorIconsContainer,
                    com.android.car.media.common.R.layout.indicator_icon, indicatorUris,
                    mMaxIndicatorIcons, mMaxArtSize);
        }

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
    protected void bindProgressUI(MediaItemMetadata mediaItemMetadata) {
        int playbackStatus = mediaItemMetadata.getPlaybackStatus();
        BrowseAdapterUtils.handleNewMediaIndicator(playbackStatus, mNewMediaDot);
        double progress = mediaItemMetadata.getProgress();
        BrowseAdapterUtils.setPlaybackProgressIndicator(mProgressbar, progress);
    }

    protected void bindBrowseCustomActions(Context context, BrowseViewData browseViewData) {
        int maxVisibleActions = context.getResources().getInteger(R.integer.max_visible_actions);

        if (mCustomActionsContainer == null) {
            if (maxVisibleActions > 0) {
                Log.e(TAG, "Custom action container null when max actions > 0");
            }
            return; //We have nothing to bind to.
        }

        mCustomActionsContainer.removeAllViews();
        mBrowseActionIcons.forEach((it) -> it.maybeCancelLoading(context));
        mBrowseActionIcons.clear();

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
            ImageViewBinder<ImageBinder.ImageRef> viewBinder =
                    new ImageViewBinder<>(mMaxArtSize, imageView);
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
            imageView.setImageResource(com.android.car.ui.R.drawable.car_ui_icon_overflow_menu);
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
