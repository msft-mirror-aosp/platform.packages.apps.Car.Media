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

import static com.android.car.ui.recyclerview.RangeFilter.INVALID_INDEX;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Preconditions;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.apps.common.imaging.ImageViewBinder;
import com.android.car.apps.common.util.ViewUtils;
import com.android.car.media.MediaActivityController.Callbacks;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.browse.MediaItemsRepository;
import com.android.car.media.common.playback.PlaybackViewModel;
import com.android.car.ui.recyclerview.CarUiRecyclerView;
import com.android.car.ui.recyclerview.ContentLimiting;
import com.android.car.ui.recyclerview.ScrollingLimitedViewHolder;
import com.android.car.uxr.CarUxRestrictionsAppConfig;
import com.android.car.uxr.LifeCycleObserverUxrContentLimiter;
import com.android.car.uxr.UxrContentLimiterImpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;


/**
 * A {@link Fragment} that implements the playback queue experience. It observes a {@link
 * PlaybackViewModel} and updates its information depending on the currently playing media source
 * through the {@link android.media.session.MediaSession} API.
 */
public class PlaybackQueueController {

    private static final String TAG = "PlaybackQueueController";

    private Callbacks mCallbacks;
    private LifeCycleObserverUxrContentLimiter mUxrContentLimiter;
    private PlaybackViewModel mPlaybackViewModel;
    private MediaItemsRepository mMediaItemsRepository;
    private QueueItemsAdapter mQueueAdapter;
    private CarUiRecyclerView mQueue;
    private PlaybackQueueCallback mPlaybackQueueCallback;
    private DefaultItemAnimator mItemAnimator;

    private PlaybackViewModel.PlaybackController mController;
    private Long mActiveQueueItemId;

    private boolean mShowTimeForActiveQueueItem;
    private boolean mShowIconForActiveQueueItem;
    private boolean mShowThumbnailForQueueItem;
    private boolean mShowSubtitleForQueueItem;

    /**
     * The callbacks used to communicate the user interactions to the queue fragment listeners.
     */
    public interface PlaybackQueueCallback {

        /**
         * Will be called when a queue item is selected by the user.
         **/
        void onQueueItemClicked(MediaItemMetadata item);
    }

    /**
     * The view holder for the queue items.
     */
    public class QueueViewHolder extends RecyclerView.ViewHolder {

        private final View mView;
        private final ViewGroup mThumbnailContainer;
        private final ImageView mThumbnail;
        private final View mSpacer;
        private final TextView mTitle;
        private final TextView mSubtitle;
        private final TextView mCurrentTime;
        private final TextView mMaxTime;
        private final TextView mTimeSeparator;
        private final ImageView mActiveIcon;

        private final ImageViewBinder<MediaItemMetadata.ArtworkRef> mThumbnailBinder;

        QueueViewHolder(View itemView) {
            super(itemView);
            mView = itemView;
            mThumbnailContainer = itemView.findViewById(R.id.thumbnail_container);
            mThumbnail = itemView.findViewById(R.id.thumbnail);
            mSpacer = itemView.findViewById(R.id.spacer);
            mTitle = itemView.findViewById(R.id.queue_list_item_title);
            mSubtitle = itemView.findViewById(R.id.queue_list_item_subtitle);
            mCurrentTime = itemView.findViewById(R.id.current_time);
            mMaxTime = itemView.findViewById(R.id.max_time);
            mTimeSeparator = itemView.findViewById(R.id.separator);
            mActiveIcon = itemView.findViewById(R.id.now_playing_icon);

            Size maxArtSize = MediaAppConfig.getMediaItemsBitmapMaxSize(itemView.getContext());
            mThumbnailBinder = new ImageViewBinder<>(maxArtSize, mThumbnail);
        }

        void bind(MediaItemMetadata item) {
            mView.setOnClickListener(v -> onQueueItemClicked(item));

            ViewUtils.setVisible(mThumbnailContainer, mShowThumbnailForQueueItem);
            if (mShowThumbnailForQueueItem) {
                Context context = mView.getContext();
                mThumbnailBinder.setImage(context, item != null ? item.getArtworkKey() : null);
            }

            ViewUtils.setVisible(mSpacer, !mShowThumbnailForQueueItem);

            mTitle.setText(item.getTitle());

            boolean active = mActiveQueueItemId != null && Objects.equals(mActiveQueueItemId,
                    item.getQueueId());
            if (active) {
                mCurrentTime.setText(mQueueAdapter.getCurrentTime());
                mMaxTime.setText(mQueueAdapter.getMaxTime());
            }
            boolean shouldShowTime =
                    mShowTimeForActiveQueueItem && active && mQueueAdapter.getTimeVisible();
            ViewUtils.setVisible(mCurrentTime, shouldShowTime);
            ViewUtils.setVisible(mMaxTime, shouldShowTime);
            ViewUtils.setVisible(mTimeSeparator, shouldShowTime);

            mView.setSelected(active);

            boolean shouldShowIcon = mShowIconForActiveQueueItem && active;
            ViewUtils.setVisible(mActiveIcon, shouldShowIcon);

            if (mShowSubtitleForQueueItem) {
                mSubtitle.setText(item.getSubtitle());
            }
        }

        void onViewAttachedToWindow() {
            if (mShowThumbnailForQueueItem) {
                Context context = mView.getContext();
                mThumbnailBinder.maybeRestartLoading(context);
            }
        }

        void onViewDetachedFromWindow() {
            if (mShowThumbnailForQueueItem) {
                Context context = mView.getContext();
                mThumbnailBinder.maybeCancelLoading(context);
            }
        }
    }

    /** Returns the maximum number of items in the queue under driving restrictions. */
    public static int getMaxItemsWhileRestricted(Context context) {
        Integer maxItems = CarUxRestrictionsAppConfig.getContentLimit(context,
                R.xml.uxr_config, R.id.playback_fragment_now_playing_list_uxr_config);
        Preconditions.checkNotNull(maxItems, "Misconfigured list limits.");
        return (maxItems <= 0) ? -1 : UxrPivotFilterImpl.adjustMaxItems(maxItems);
    }

    private class QueueItemsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>
            implements ContentLimiting {

        private static final int CLAMPED_MESSAGE_VIEW_TYPE = -1;
        private static final int QUEUE_ITEM_VIEW_TYPE = 0;

        private UxrPivotFilter mUxrPivotFilter;
        private List<MediaItemMetadata> mQueueItems = Collections.emptyList();
        private String mCurrentTimeText = "";
        private String mMaxTimeText = "";
        /**
         * Index in {@link #mQueueItems}.
         */
        private Integer mActiveItemIndex;
        private boolean mTimeVisible;
        private Integer mScrollingLimitedMessageResId;

        QueueItemsAdapter() {
            mUxrPivotFilter = UxrPivotFilter.PASS_THROUGH;
        }

        @Override
        public void setMaxItems(int maxItems) {
            if (maxItems >= 0) {
                mUxrPivotFilter = new UxrPivotFilterImpl(this, maxItems);
            } else {
                mUxrPivotFilter = UxrPivotFilter.PASS_THROUGH;
            }
            applyFilterToQueue();
        }

        @Override
        public void setScrollingLimitedMessageResId(int resId) {
            if (mScrollingLimitedMessageResId == null || mScrollingLimitedMessageResId != resId) {
                mScrollingLimitedMessageResId = resId;
                mUxrPivotFilter.invalidateMessagePositions();
            }
        }

        @Override
        public int getConfigurationId() {
            return R.id.playback_fragment_now_playing_list_uxr_config;
        }

        void setItems(@Nullable List<MediaItemMetadata> items) {
            List<MediaItemMetadata> newQueueItems =
                    new ArrayList<>(items != null ? items : Collections.emptyList());
            if (newQueueItems.equals(mQueueItems)) {
                return;
            }
            mQueueItems = newQueueItems;
            updateActiveItem(/* listIsNew */ true);
        }

        private int getActiveItemIndex() {
            return mActiveItemIndex != null ? mActiveItemIndex : 0;
        }

        private int getQueueSize() {
            return (mQueueItems != null) ? mQueueItems.size() : 0;
        }


        /**
         * Returns the position of the active item if there is one, otherwise returns
         *
         * @link UxrPivotFilter#INVALID_POSITION}.
         */
        private int getActiveItemPosition() {
            if (mActiveItemIndex == null) {
                return UxrPivotFilter.INVALID_POSITION;
            }
            return mUxrPivotFilter.indexToPosition(mActiveItemIndex);
        }

        private void invalidateActiveItemPosition() {
            int position = getActiveItemPosition();
            if (position != UxrPivotFilterImpl.INVALID_POSITION) {
                notifyItemChanged(position);
            }
        }

        private void scrollToActiveItemPosition() {
            int position = getActiveItemPosition();
            if (position != UxrPivotFilterImpl.INVALID_POSITION) {
                mQueue.scrollToPosition(position);
            }
        }

        private void applyFilterToQueue() {
            mUxrPivotFilter.recompute(getQueueSize(), getActiveItemIndex());
            notifyDataSetChanged();
        }

        /**
         * Implements findFirstCompletelyVisibleItemPosition with range filter
         * <p>
         *     Converts position in RV to index in adapter data.
         * </p>
         */
        public int findFirstVisibleItemIndex() {
            int rvPos = mQueue.findFirstCompletelyVisibleItemPosition();
            if (rvPos == RecyclerView.NO_POSITION) return RecyclerView.NO_POSITION;
            if (mUxrPivotFilter.positionToIndex(rvPos) == INVALID_INDEX) rvPos++;
            return mUxrPivotFilter.positionToIndex(rvPos);
        }

        /**
         * Implements findLastCompletelyVisibleItemPosition with range filter
         * <p>
         *     Converts position in RV to index in adapter data.
         * </p>
         */
        public int findLastVisibleItemIndex() {
            int rvPos = mQueue.findLastCompletelyVisibleItemPosition();
            if (rvPos == RecyclerView.NO_POSITION) return RecyclerView.NO_POSITION;
            if (mUxrPivotFilter.positionToIndex(rvPos) == INVALID_INDEX) rvPos--;
            return mUxrPivotFilter.positionToIndex(rvPos);
        }

        // Updates mActiveItemPos, then scrolls the queue to mActiveItemPos.
        // It should be called when the active item (mActiveQueueItemId) changed or
        // the queue items (mQueueItems) changed.
        void updateActiveItem(boolean listIsNew) {
            if (mQueueItems == null || mActiveQueueItemId == null) {
                mActiveItemIndex = null;
                applyFilterToQueue();
                return;
            }
            Integer activeItemPos = null;
            for (int i = 0; i < mQueueItems.size(); i++) {
                if (Objects.equals(mQueueItems.get(i).getQueueId(), mActiveQueueItemId)) {
                    activeItemPos = i;
                    break;
                }
            }

            // Invalidate the previous active item so it gets redrawn as a normal one.
            invalidateActiveItemPosition();

            mActiveItemIndex = activeItemPos;
            if (listIsNew) {
                applyFilterToQueue();
            } else {
                mUxrPivotFilter.updatePivotIndex(getActiveItemIndex());
            }

            scrollToActiveItemPosition();
            invalidateActiveItemPosition();
        }

        void setCurrentTime(String currentTime) {
            if (!mCurrentTimeText.equals(currentTime)) {
                mCurrentTimeText = currentTime;
                invalidateActiveItemPosition();
            }
        }

        void setMaxTime(String maxTime) {
            if (!mMaxTimeText.equals(maxTime)) {
                mMaxTimeText = maxTime;
                invalidateActiveItemPosition();
            }
        }

        void setTimeVisible(boolean visible) {
            if (mTimeVisible != visible) {
                mTimeVisible = visible;
                invalidateActiveItemPosition();
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
        public final int getItemViewType(int position) {
            if (mUxrPivotFilter.positionToIndex(position) == UxrPivotFilterImpl.INVALID_INDEX) {
                return CLAMPED_MESSAGE_VIEW_TYPE;
            } else {
                return QUEUE_ITEM_VIEW_TYPE;
            }
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == CLAMPED_MESSAGE_VIEW_TYPE) {
                return ScrollingLimitedViewHolder.create(parent);
            }
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            return new QueueViewHolder(inflater.inflate(R.layout.queue_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder vh, int position) {
            if (vh instanceof QueueViewHolder) {
                int index = mUxrPivotFilter.positionToIndex(position);
                if (index != UxrPivotFilterImpl.INVALID_INDEX) {
                    int size = mQueueItems.size();
                    if (0 <= index && index < size) {
                        QueueViewHolder holder = (QueueViewHolder) vh;
                        holder.bind(mQueueItems.get(index));
                    } else {
                        Log.e(TAG, "onBindViewHolder pos: " + position + " gave index: "
                                + index + " out of bounds size: " + size + " "
                                + mUxrPivotFilter.toString());
                    }
                } else {
                    Log.e(TAG, "onBindViewHolder invalid position " + position + " "
                            + mUxrPivotFilter.toString());
                }
            } else if (vh instanceof ScrollingLimitedViewHolder) {
                ScrollingLimitedViewHolder holder = (ScrollingLimitedViewHolder) vh;
                holder.bind(mScrollingLimitedMessageResId);
            } else {
                throw new IllegalArgumentException("unknown holder class " + vh.getClass());
            }
        }

        @Override
        public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder vh) {
            super.onViewAttachedToWindow(vh);
            if (vh instanceof QueueViewHolder) {
                QueueViewHolder holder = (QueueViewHolder) vh;
                holder.onViewAttachedToWindow();
            }
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder vh) {
            super.onViewDetachedFromWindow(vh);
            if (vh instanceof QueueViewHolder) {
                QueueViewHolder holder = (QueueViewHolder) vh;
                holder.onViewDetachedFromWindow();
            }
        }

        @Override
        public int getItemCount() {
            return mUxrPivotFilter.getFilteredCount();
        }

        @Override
        public long getItemId(int position) {
            int index = mUxrPivotFilter.positionToIndex(position);
            if (index != UxrPivotFilterImpl.INVALID_INDEX) {
                return mQueueItems.get(position).getQueueId();
            } else {
                return RecyclerView.NO_ID;
            }
        }
    }

    private static class QueueTopItemDecoration extends RecyclerView.ItemDecoration {
        int mHeight;
        int mDecorationPosition;

        QueueTopItemDecoration(int height, int decorationPosition) {
            mHeight = height;
            mDecorationPosition = decorationPosition;
        }

        @Override
        public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
                RecyclerView.State state) {
            super.getItemOffsets(outRect, view, parent, state);
            if (parent.getChildAdapterPosition(view) == mDecorationPosition) {
                outRect.top = mHeight;
            }
        }
    }

    public PlaybackQueueController(
            ViewGroup container,
            @LayoutRes int resource,
            Callbacks callbacks,
            PlaybackViewModel playbackViewModel,
            MediaItemsRepository itemsRepository) {

        FragmentActivity activity = callbacks.getActivity();
        mCallbacks = callbacks;
        mPlaybackViewModel = playbackViewModel;
        mMediaItemsRepository = itemsRepository;

        LayoutInflater inflater = LayoutInflater.from(container.getContext());
        View view = inflater.inflate(resource, container, false);
        container.addView(view);

        Resources res = view.getContext().getResources();
        mQueue = view.findViewById(R.id.queue_list);

        mShowTimeForActiveQueueItem = res.getBoolean(
                R.bool.show_time_for_now_playing_queue_list_item);
        mShowIconForActiveQueueItem = res.getBoolean(
                R.bool.show_icon_for_now_playing_queue_list_item);
        mShowThumbnailForQueueItem = view.getContext().getResources().getBoolean(
                R.bool.show_thumbnail_for_queue_list_item);
        mShowSubtitleForQueueItem = view.getContext().getResources().getBoolean(
                R.bool.show_subtitle_for_queue_list_item);

        mPlaybackViewModel.getPlaybackController().observe(activity,
                controller -> mController = controller);
        initQueue();

        mUxrContentLimiter = new LifeCycleObserverUxrContentLimiter(
                new UxrContentLimiterImpl(view.getContext(), R.xml.uxr_config));
        mUxrContentLimiter.setAdapter(mQueueAdapter);
        activity.getLifecycle().addObserver(mUxrContentLimiter);
    }

    public void setCallback(PlaybackQueueCallback callback) {
        mPlaybackQueueCallback = callback;
    }

    private void initQueue() {

        int decorationHeight = getActivity().getResources().getDimensionPixelSize(
                R.dimen.playback_queue_list_padding_top);
        // TODO (b/206038962): addItemDecoration is not supported anymore. Find another way to
        // support this.
        // Put the decoration above the first item.
        int decorationPosition = 0;
        mQueue.addItemDecoration(new QueueTopItemDecoration(decorationHeight, decorationPosition));

        mQueue.setVerticalFadingEdgeEnabled(
                getActivity().getResources().getBoolean(R.bool.queue_fading_edge_length_enabled));
        mQueueAdapter = new QueueItemsAdapter();

        mPlaybackViewModel.getPlaybackStateWrapper().observe(getActivity(),
                state -> {
                    Long itemId = (state != null) ? state.getActiveQueueItemId() : null;
                    if (!Objects.equals(mActiveQueueItemId, itemId)) {
                        mActiveQueueItemId = itemId;
                        mQueueAdapter.updateActiveItem(/* listIsNew */ false);
                    }
                });
        mQueue.setAdapter(mQueueAdapter);
        mQueue.addOnScrollListener(new CarUiRecyclerView.OnScrollListener() {
            List<String> mPrevVisible = new ArrayList<>();

            @Override
            public void onScrolled(CarUiRecyclerView recyclerView, int dx, int dy) {
                //dx and dy are 0 when items in RV change or layout is requested. We should
                // use this to trigger querying what is visible.
                sendScrollEvent(dx != 0 || dy != 0);
            }

            @Override
            public void onScrollStateChanged(CarUiRecyclerView recyclerView, int newState) {}

            private void sendScrollEvent(boolean fromScroll) {
                int currFirst = mQueueAdapter.findFirstVisibleItemIndex();
                int currLast = mQueueAdapter.findLastVisibleItemIndex();
                mPrevVisible = AnalyticsHelper.sendVisibleItemsInc(mMediaItemsRepository, null,
                        mPrevVisible, mQueueAdapter.mQueueItems, currFirst, currLast, fromScroll);
            }
        });
        // Disable item changed animation.
        mItemAnimator = new DefaultItemAnimator();
        mItemAnimator.setSupportsChangeAnimations(false);
        mQueue.setItemAnimator(mItemAnimator);
        mPlaybackViewModel.getQueue().observe(getActivity(), this::setQueue);

        mPlaybackViewModel.getProgress().observe(
                getActivity(),
                playbackProgress -> {
                    mQueueAdapter.setCurrentTime(playbackProgress.getCurrentTimeText().toString());
                    mQueueAdapter.setMaxTime(playbackProgress.getMaxTimeText().toString());
                    mQueueAdapter.setTimeVisible(playbackProgress.hasTime());
                });
    }

    void setQueue(List<MediaItemMetadata> queueItems) {
        mQueueAdapter.setItems(queueItems);
    }

    private void onQueueItemClicked(MediaItemMetadata item) {
        if (mController != null) {
            mController.skipToQueueItem(item.getQueueId());
        }
        if (mPlaybackQueueCallback != null) {
            mPlaybackQueueCallback.onQueueItemClicked(item);
        }
    }

    private FragmentActivity getActivity() {
        return mCallbacks.getActivity();
    }
}
