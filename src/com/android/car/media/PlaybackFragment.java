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

package com.android.car.media;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.apps.common.util.ViewUtils;
import com.android.car.media.common.MediaAppSelectorWidget;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.MetadataController;
import com.android.car.media.common.PlaybackControlsActionBar;
import com.android.car.media.common.playback.PlaybackViewModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A {@link Fragment} that implements both the playback and the content forward browsing experience.
 * It observes a {@link PlaybackViewModel} and updates its information depending on the currently
 * playing media source through the {@link android.media.session.MediaSession} API.
 */
public class PlaybackFragment extends Fragment {
    private static final String TAG = "PlaybackFragment";

    private View mBackgroundScrim;
    private View mControlBarScrim;
    private PlaybackControlsActionBar mPlaybackControls;
    private QueueItemsAdapter mQueueAdapter;
    private RecyclerView mQueue;
    private ConstraintLayout mMetadataContainer;
    private SeekBar mSeekBar;
    private View mQueueButton;
    private ViewGroup mNavIconContainer;

    private DefaultItemAnimator mItemAnimator;

    private MetadataController mMetadataController;

    private PlaybackFragmentListener mListener;

    private PlaybackViewModel.PlaybackController mController;
    private Long mActiveQueueItemId;

    private boolean mHasQueue;
    private boolean mQueueIsVisible;

    private int mFadeDuration;
    private float mPlaybackQueueBackgroundAlpha;

    /**
     * PlaybackFragment listener
     */
    public interface PlaybackFragmentListener {
        /**
         * Invoked when the user clicks on the collapse button
         */
        void onCollapse();

        /** Invoked when the user clicks on the queue button. */
        void onQueueClicked();
    }

    public class QueueViewHolder extends RecyclerView.ViewHolder {

        private final View mView;
        private final TextView mTitle;
        private final TextView mCurrentTime;
        private final TextView mMaxTime;
        private final TextView mTimeSeparator;

        QueueViewHolder(View itemView) {
            super(itemView);
            mView = itemView;
            mTitle = itemView.findViewById(R.id.title);
            mCurrentTime = itemView.findViewById(R.id.current_time);
            mMaxTime = itemView.findViewById(R.id.max_time);
            mTimeSeparator = itemView.findViewById(R.id.separator);
        }

        boolean bind(MediaItemMetadata item) {
            mView.setOnClickListener(v -> onQueueItemClicked(item));

            mTitle.setText(item.getTitle());
            boolean active = mActiveQueueItemId != null && Objects.equals(mActiveQueueItemId,
                    item.getQueueId());
            boolean shouldShowTime = active && mQueueAdapter.getTimeVisible();
            ViewUtils.setVisible(mCurrentTime, shouldShowTime);
            ViewUtils.setVisible(mMaxTime, shouldShowTime);
            ViewUtils.setVisible(mTimeSeparator, shouldShowTime);
            if (active) {
                mCurrentTime.setText(mQueueAdapter.getCurrentTime());
                mMaxTime.setText(mQueueAdapter.getMaxTime());
            }
            return active;
        }
    }


    private class QueueItemsAdapter extends RecyclerView.Adapter<QueueViewHolder> {

        private List<MediaItemMetadata> mQueueItems;
        private String mCurrentTimeText;
        private String mMaxTimeText;
        private Integer mActiveItemPos;
        private boolean mTimeVisible;

        void setItems(@Nullable List<MediaItemMetadata> items) {
            mQueueItems = new ArrayList<>(items != null ? items : Collections.emptyList());
            notifyDataSetChanged();
        }

        void setCurrentTime(String currentTime) {
            mCurrentTimeText = currentTime;
            if (mActiveItemPos != null) {
                notifyItemChanged(mActiveItemPos.intValue());
            }
        }

        void setMaxTime(String maxTime) {
            mMaxTimeText = maxTime;
            if (mActiveItemPos != null) {
                notifyItemChanged(mActiveItemPos.intValue());
            }
        }

        void setTimeVisible(boolean visible) {
            mTimeVisible = visible;
            if (mActiveItemPos != null) {
                notifyItemChanged(mActiveItemPos.intValue());
            }
        }

        String getCurrentTime() {
            return mCurrentTimeText;
        }

        String getMaxTime() {
            return mMaxTimeText;
        }

        boolean getTimeVisible() {
            return mTimeVisible;
        }

        @Override
        public QueueViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            return new QueueViewHolder(inflater.inflate(R.layout.queue_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(QueueViewHolder holder, int position) {
            int size = mQueueItems.size();
            if (0 <= position && position < size) {
                boolean active = holder.bind(mQueueItems.get(position));
                if (active) {
                    mActiveItemPos = position;
                }
            } else {
                Log.e(TAG, "onBindViewHolder invalid position " + position + " of " + size);
            }
        }

        @Override
        public int getItemCount() {
            return mQueueItems.size();
        }

        void refresh() {
            // TODO: Perform a diff between current and new content and trigger the proper
            // RecyclerView updates.
            this.notifyDataSetChanged();
        }

        @Override
        public long getItemId(int position) {
            return mQueueItems.get(position).getQueueId();
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, final ViewGroup container,
            Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_playback, container, false);
        mQueue = view.findViewById(R.id.queue_list);
        mMetadataContainer = view.findViewById(R.id.metadata_container);
        mSeekBar = view.findViewById(R.id.seek_bar);
        mQueueButton = view.findViewById(R.id.queue_button);
        mQueueButton.setOnClickListener(button -> onQueueClicked());
        mNavIconContainer = view.findViewById(R.id.nav_icon_container);
        mNavIconContainer.setOnClickListener(nav -> onCollapse());
        mBackgroundScrim = view.findViewById(R.id.background_scrim);
        ViewUtils.setVisible(mBackgroundScrim, false);
        mControlBarScrim = view.findViewById(R.id.control_bar_scrim);
        ViewUtils.setVisible(mControlBarScrim, false);
        mControlBarScrim.setOnClickListener(scrim -> mPlaybackControls.close());
        mControlBarScrim.setClickable(false);

        boolean useMediaSourceColor =
                getContext().getResources().getBoolean(
                        R.bool.use_media_source_color_for_progress_bar);
        int defaultColor = getContext().getResources().getColor(
                R.color.progress_bar_highlight, null);
        if (useMediaSourceColor) {
            getPlaybackViewModel().getMediaSourceColors().observe(getViewLifecycleOwner(),
                    sourceColors -> {
                        int color = sourceColors != null ? sourceColors.getAccentColor(defaultColor)
                                : defaultColor;
                        mSeekBar.setThumbTintList(ColorStateList.valueOf(color));
                        mSeekBar.setProgressTintList(ColorStateList.valueOf(color));
                    });
        } else {
            mSeekBar.setThumbTintList(ColorStateList.valueOf(defaultColor));
            mSeekBar.setProgressTintList(ColorStateList.valueOf(defaultColor));
        }

        MediaAppSelectorWidget appIcon = view.findViewById(R.id.app_icon_container);
        appIcon.setFragmentActivity(getActivity());

        getPlaybackViewModel().getPlaybackController().observe(getViewLifecycleOwner(),
                controller -> mController = controller);
        initPlaybackControls(view.findViewById(R.id.playback_controls));
        initMetadataController(view);
        initQueue();
        return view;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
    }

    @Override
    public void onDetach() {
        super.onDetach();
    }

    private void initPlaybackControls(PlaybackControlsActionBar playbackControls) {
        mPlaybackControls = playbackControls;
        mPlaybackControls.setModel(getPlaybackViewModel(), getViewLifecycleOwner());
        mPlaybackControls.registerExpandCollapseCallback((expanding) -> {
            mControlBarScrim.setClickable(expanding);
            if (expanding) {
                ViewUtils.showViewAnimated(mControlBarScrim, getContext().getResources().getInteger(
                        R.integer.control_bar_expand_anim_duration));
            } else {
                ViewUtils.hideViewAnimated(mControlBarScrim, getContext().getResources().getInteger(
                        R.integer.control_bar_collapse_anim_duration));
            }
        });
    }

    private void initQueue() {
        mFadeDuration = getResources().getInteger(
                R.integer.fragment_playback_queue_fade_duration_ms);
        mPlaybackQueueBackgroundAlpha = getResources().getFloat(
                R.dimen.playback_queue_background_alpha);

        mQueue.setVerticalFadingEdgeEnabled(
                getResources().getBoolean(R.bool.queue_fading_edge_length_enabled));
        mQueueAdapter = new QueueItemsAdapter();

        getPlaybackViewModel().getPlaybackStateWrapper().observe(getViewLifecycleOwner(),
                state -> {
                    Long itemId = (state != null) ? state.getActiveQueueItemId() : null;
                    if (!Objects.equals(mActiveQueueItemId, itemId)) {
                        mActiveQueueItemId = itemId;
                        mQueueAdapter.refresh();
                    }
                });
        mQueue.setAdapter(mQueueAdapter);
        mQueue.setLayoutManager(new LinearLayoutManager(getContext()));

        // Disable item changed animation.
        mItemAnimator = new DefaultItemAnimator();
        mItemAnimator.setSupportsChangeAnimations(false);
        mQueue.setItemAnimator(mItemAnimator);

        getPlaybackViewModel().getQueue().observe(this, this::setQueue);

        getPlaybackViewModel().hasQueue().observe(getViewLifecycleOwner(), hasQueue -> {
            boolean enableQueue = (hasQueue != null) && hasQueue;
            setHasQueue(enableQueue);
            if (mQueueIsVisible && !enableQueue) {
                toggleQueueVisibility();
            }
        });
        getPlaybackViewModel().getProgress().observe(getViewLifecycleOwner(),
                playbackProgress ->
                {
                    mQueueAdapter.setCurrentTime(playbackProgress.getCurrentTimeText().toString());
                    mQueueAdapter.setMaxTime(playbackProgress.getMaxTimeText().toString());
                    mQueueAdapter.setTimeVisible(playbackProgress.hasTime());
                });
    }

    private void setQueue(List<MediaItemMetadata> queueItems) {
        mQueueAdapter.setItems(queueItems);
        mQueueAdapter.refresh();
    }

    private void initMetadataController(View view) {
        ImageView albumArt = view.findViewById(R.id.album_art);
        TextView title = view.findViewById(R.id.title);
        TextView artist = view.findViewById(R.id.artist);
        TextView albumTitle = view.findViewById(R.id.album_title);
        TextView outerSeparator = view.findViewById(R.id.outer_separator);
        TextView curTime = view.findViewById(R.id.current_time);
        TextView innerSeparator = view.findViewById(R.id.inner_separator);
        TextView maxTime = view.findViewById(R.id.max_time);
        SeekBar seekbar = view.findViewById(R.id.seek_bar);

        mMetadataController = new MetadataController(getViewLifecycleOwner(),
                getPlaybackViewModel(), title, artist, albumTitle, outerSeparator,
                curTime, innerSeparator, maxTime, seekbar, albumArt,
                getResources().getDimensionPixelSize(R.dimen.playback_album_art_size));
    }

    /**
     * Hides or shows the playback queue.
     */
    public void toggleQueueVisibility() {
        mQueueIsVisible = !mQueueIsVisible;
        mQueueButton.setActivated(mQueueIsVisible);
        if (mQueueIsVisible) {
            ViewUtils.hideViewAnimated(mMetadataContainer, mFadeDuration);
            ViewUtils.hideViewAnimated(mSeekBar, mFadeDuration);
            ViewUtils.showViewAnimated(mQueue, mFadeDuration);
            ViewUtils.showViewAnimated(mBackgroundScrim, mFadeDuration);
        } else {
            ViewUtils.hideViewAnimated(mQueue, mFadeDuration);
            ViewUtils.showViewAnimated(mMetadataContainer, mFadeDuration);
            ViewUtils.showViewAnimated(mSeekBar, mFadeDuration);
            ViewUtils.hideViewAnimated(mBackgroundScrim, mFadeDuration);
        }
    }

    /**
     * Whether the playback queue is visible.
     */
    public boolean isQueueVisible() {
        return mQueueIsVisible;
    }

    /** Sets whether the source has a queue. */
    private void setHasQueue(boolean hasQueue) {
        mHasQueue = hasQueue;
        updateQueueVisibility();
    }

    private void updateQueueVisibility() {
        mQueueButton.setVisibility(mHasQueue ? View.VISIBLE : View.GONE);
    }

    private void onQueueItemClicked(MediaItemMetadata item) {
        if (mController != null) {
            mController.skipToQueueItem(item.getQueueId());
        }
        onQueueClicked();
    }

    /**
     * Collapses the playback controls.
     */
    public void closeOverflowMenu() {
        mPlaybackControls.close();
    }

    private PlaybackViewModel getPlaybackViewModel() {
        return PlaybackViewModel.get(getActivity().getApplication());
    }

    /**
     * Sets a listener of this PlaybackFragment events. In order to avoid memory leaks, consumers
     * must reset this reference by setting the listener to null.
     */
    public void setListener(PlaybackFragmentListener listener) {
        mListener = listener;
    }

    private void onCollapse() {
        if (mListener != null) {
            mListener.onCollapse();
        }
    }

    private void onQueueClicked() {
        if (mListener != null) {
            mListener.onQueueClicked();
        }
        mQueueButton.setSelected(!mQueueButton.isSelected());
    }
}
