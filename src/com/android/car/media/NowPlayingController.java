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

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.util.Size;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.util.Preconditions;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.android.car.apps.common.BackgroundImageView;
import com.android.car.apps.common.imaging.ImageBinder;
import com.android.car.apps.common.imaging.ImageBinder.PlaceholderType;
import com.android.car.apps.common.util.ViewUtils;
import com.android.car.media.MediaActivityController.Callbacks;
import com.android.car.media.PlaybackQueueController.PlaybackQueueCallback;
import com.android.car.media.common.ContentFormatView;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.MetadataController;
import com.android.car.media.common.PlaybackControlsActionBar;
import com.android.car.media.common.browse.MediaItemsRepository;
import com.android.car.media.common.playback.PlaybackViewModel;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.extensions.analytics.event.AnalyticsEvent;
import com.android.car.media.widgets.AppBarController;
import com.android.car.ui.core.CarUi;
import com.android.car.ui.toolbar.MenuItem;
import com.android.car.ui.toolbar.NavButtonMode;
import com.android.car.ui.toolbar.ToolbarController;
import com.android.car.ui.utils.DirectManipulationHelper;

import java.util.ArrayList;
import java.util.List;


/**
 * A {@link Fragment} that implements both the playback and the content forward browsing experience.
 * It observes a {@link PlaybackViewModel} and updates its information depending on the currently
 * playing media source through the {@link android.media.session.MediaSession} API.
 */
public class NowPlayingController {
    private static final String TAG = "NowPlayingController";

    private final Callbacks mCallbacks;
    private ImageBinder<MediaItemMetadata.ArtworkRef> mAlbumArtBinder;
    private AppBarController mAppBarController;
    private BackgroundImageView mAlbumBackground;
    private PlaybackQueueController mPlaybackQueueController;
    private View mBackgroundScrim;
    private View mControlBarScrim;
    private PlaybackControlsActionBar mPlaybackControls;
    private PlaybackViewModel mPlaybackViewModel;
    private MediaItemsRepository mMediaItemsRepository;
    private ViewGroup mSeekBarContainer;
    private SeekBar mSeekBar;
    private List<View> mViewsToHideForCustomActions;
    private List<View> mViewsToHideWhenQueueIsVisible;
    private List<View> mViewsToShowWhenQueueIsVisible;
    private List<View> mViewsToHideImmediatelyWhenQueueIsVisible;
    private List<View> mViewsToShowImmediatelyWhenQueueIsVisible;

    private MetadataController mMetadataController;

    private NowPlayingListener mListener;

    private boolean mHasQueue;
    private boolean mQueueIsVisible;
    private boolean mShowLinearProgressBar;

    private int mFadeDuration;

    private MenuItem mQueueMenuItem;

    private PlaybackQueueController.PlaybackQueueCallback mPlaybackQueueCallback =
            new PlaybackQueueCallback() {
        @Override
        public void onQueueItemClicked(MediaItemMetadata item) {
            boolean switchToPlayback = getActivity().getResources().getBoolean(
                    R.bool.switch_to_playback_view_when_playable_item_is_clicked);
            if (item.getId() != null) {
                //Send analytics click event
                mMediaItemsRepository.getAnalyticsManager()
                        .sendMediaClickedEvent(item.getId(), AnalyticsEvent.QUEUE_LIST);
            }
            if (switchToPlayback) {
                toggleQueueVisibility();
            }
        }
    };

    /**
     * PlaybackFragment listener
     */
    public interface NowPlayingListener {
        /**
         * Invoked when the user clicks on a browse link
         */
        void goToMediaItem(MediaSource source, MediaItemMetadata mediaItem);
    }

    public NowPlayingController(
            Callbacks callbacks,
            View view,
            PlaybackViewModel playbackViewModel,
            MediaItemsRepository itemsRepository) {

        FragmentActivity activity = callbacks.getActivity();
        mCallbacks = callbacks;
        mPlaybackViewModel = playbackViewModel;
        mMediaItemsRepository = itemsRepository;

        Resources res = view.getContext().getResources();
        mAlbumBackground = view.findViewById(R.id.playback_background);

        ViewGroup queueContainer = view.findViewById(R.id.queue_fragment_container);
        mPlaybackQueueController = new PlaybackQueueController(
                queueContainer, R.layout.fragment_playback_queue, callbacks, playbackViewModel,
                itemsRepository);
        mPlaybackQueueController.setCallback(mPlaybackQueueCallback);

        mSeekBarContainer = view.findViewById(R.id.playback_seek_bar_container);
        mSeekBar = view.findViewById(R.id.playback_seek_bar);
        DirectManipulationHelper.setSupportsRotateDirectly(mSeekBar, true);

        GuidelinesUpdater updater = new GuidelinesUpdater(view);
        ToolbarController toolbarController = CarUi.installBaseLayoutAround(view, updater, true);
        mAppBarController = new AppBarController(view.getContext(), mMediaItemsRepository,
                toolbarController, R.xml.menuitems_playback,
                res.getBoolean(R.bool.use_media_source_logo_for_app_selector_in_playback_view));

        mAppBarController.setTitle(R.string.fragment_playback_title);
        mAppBarController.setBackgroundShown(false);
        mAppBarController.setNavButtonMode(NavButtonMode.DOWN);

        mQueueMenuItem = mAppBarController.getMenuItem(R.id.menu_item_queue);
        Preconditions.checkNotNull(mQueueMenuItem);
        mQueueMenuItem.setOnClickListener((item) -> toggleQueueVisibility());

        // Update toolbar's logo
        mPlaybackViewModel.getMediaSource().observe(activity, mediaSource ->
                mAppBarController.setLogo(mediaSource != null
                    ? new BitmapDrawable(
                        view.getContext().getResources(), mediaSource.getCroppedPackageIcon())
                    : null));

        mBackgroundScrim = view.findViewById(R.id.background_scrim);
        ViewUtils.setVisible(mBackgroundScrim, false);
        mControlBarScrim = view.findViewById(R.id.control_bar_scrim);
        if (mControlBarScrim != null) {
            ViewUtils.setVisible(mControlBarScrim, false);
            mControlBarScrim.setOnClickListener(scrim -> mPlaybackControls.close());
            mControlBarScrim.setClickable(false);
        }

        mShowLinearProgressBar = view.getContext().getResources().getBoolean(
                R.bool.show_linear_progress_bar);

        if (mSeekBar != null) {
            if (mShowLinearProgressBar) {
                boolean useMediaSourceColor = res.getBoolean(
                        R.bool.use_media_source_color_for_progress_bar);
                int defaultColor = res.getColor(R.color.progress_bar_highlight, null);
                if (useMediaSourceColor) {
                    mPlaybackViewModel.getMediaSourceColors().observe(activity,
                            sourceColors -> {
                                int color = sourceColors != null
                                        ? sourceColors.getAccentColor(defaultColor)
                                        : defaultColor;
                                setSeekBarColor(color);
                        });
                } else {
                    setSeekBarColor(defaultColor);
                }
            } else {
                mSeekBar.setVisibility(View.GONE);
            }
        }

        initPlaybackControls(view.findViewById(R.id.playback_controls));
        initMetadataController(view);
        initQueue();

        // Don't update the visibility of seekBar if show_linear_progress_bar is false.
        ViewUtils.Filter ignoreSeekBarFilter =
                (viewToFilter) -> mShowLinearProgressBar || viewToFilter != mSeekBarContainer;

        mViewsToHideForCustomActions = ViewUtils.getViewsById(view, res,
            R.array.playback_views_to_hide_when_showing_custom_actions, ignoreSeekBarFilter);
        mViewsToHideWhenQueueIsVisible = ViewUtils.getViewsById(view, res,
            R.array.playback_views_to_hide_when_queue_is_visible, ignoreSeekBarFilter);
        mViewsToShowWhenQueueIsVisible = ViewUtils.getViewsById(view, res,
            R.array.playback_views_to_show_when_queue_is_visible, null);
        mViewsToHideImmediatelyWhenQueueIsVisible = ViewUtils.getViewsById(view, res,
            R.array.playback_views_to_hide_immediately_when_queue_is_visible, ignoreSeekBarFilter);
        mViewsToShowImmediatelyWhenQueueIsVisible = ViewUtils.getViewsById(view, res,
            R.array.playback_views_to_show_immediately_when_queue_is_visible, null);

        mAlbumArtBinder = new ImageBinder<>(
                PlaceholderType.BACKGROUND,
                MediaAppConfig.getMediaItemsBitmapMaxSize(view.getContext()),
                drawable -> mAlbumBackground.setBackgroundDrawable(drawable));

        mPlaybackViewModel.getMetadata().observe(activity, (item) -> {
            mAlbumArtBinder.setImage(view.getContext(), item != null
                    ? item.getArtworkKey() : null);
            if (item != null && view.isShown()) {
                ArrayList<String> items = new ArrayList<>();
                items.add(item.getId());
                mMediaItemsRepository.getAnalyticsManager().sendVisibleItemsEvents(null,
                        AnalyticsEvent.PLAYBACK, AnalyticsEvent.SHOW,
                        AnalyticsEvent.NONE, items);
            }
        });
    }

    private void initPlaybackControls(PlaybackControlsActionBar playbackControls) {
        mPlaybackControls = playbackControls;
        mPlaybackControls.setModel(mPlaybackViewModel, getActivity());
        mPlaybackControls.registerExpandCollapseCallback((expanding) -> {
            Resources res = getActivity().getResources();
            int millis = expanding ? res.getInteger(R.integer.control_bar_expand_anim_duration) :
                    res.getInteger(R.integer.control_bar_collapse_anim_duration);

            if (mControlBarScrim != null) {
                mControlBarScrim.setClickable(expanding);
            }

            if (expanding) {
                if (mControlBarScrim != null) {
                    ViewUtils.showViewAnimated(mControlBarScrim, millis);
                }
            } else {
                if (mControlBarScrim != null) {
                    ViewUtils.hideViewAnimated(mControlBarScrim, millis);
                }
            }

            if (!mQueueIsVisible) {
                for (View view : mViewsToHideForCustomActions) {
                    if (expanding) {
                        ViewUtils.hideViewAnimated(view, millis);
                    } else {
                        ViewUtils.showViewAnimated(view, millis);
                    }
                }
            }
        });
    }

    private void initQueue() {
        mFadeDuration = getActivity().getResources().getInteger(
                R.integer.fragment_playback_queue_fade_duration_ms);

        // Make sure the AppBar menu reflects the initial state of playback fragment.
        updateAppBarMenu(mHasQueue);
        if (mQueueMenuItem != null) {
            mQueueMenuItem.setActivated(mQueueIsVisible);
        }

        mPlaybackViewModel.hasQueue().observe(getActivity(),
                hasQueue -> {
                    boolean enableQueue = (hasQueue != null) && hasQueue;
                    boolean isQueueVisible = enableQueue && mCallbacks.getQueueVisible();
                    setQueueState(enableQueue, isQueueVisible);
                });
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
        SeekBar seekbar = mShowLinearProgressBar ? mSeekBar : null;
        ContentFormatView contentFormat = view.findViewById(R.id.content_format);

        View logoSeparatorView = view.findViewById(R.id.logo_separator);
        View viewSeparatedFromLogo = view.findViewWithTag("view_separated_from_logo");

        Size maxArtSize = MediaAppConfig.getMediaItemsBitmapMaxSize(view.getContext());
        mMetadataController = new MetadataController(getActivity(), mPlaybackViewModel,
                mMediaItemsRepository, title, artist, albumTitle, outerSeparator, curTime,
                innerSeparator, maxTime, seekbar, albumArt, null, maxArtSize, contentFormat,
                (mediaItem) -> {
                    mMediaItemsRepository.getAnalyticsManager()
                            .sendMediaClickedEvent(mediaItem.getId(), AnalyticsEvent.PLAYBACK);
                    MediaSource source = mPlaybackViewModel.getMediaSource().getValue();
                    mListener.goToMediaItem(source, mediaItem);
                });
        mMetadataController.setLogoSeparatorView(logoSeparatorView);
        mMetadataController.setViewSeparatedFromLogo(viewSeparatedFromLogo);
    }

    /**
     * Hides or shows the playback queue when the user clicks the queue button.
     */
    private void toggleQueueVisibility() {
        boolean updatedQueueVisibility = !mQueueIsVisible;
        setQueueState(mHasQueue, updatedQueueVisibility);

        // When the visibility of queue is changed by the user, save the visibility into ViewModel
        // so that we can restore PlaybackFragment properly when needed. If it's changed by media
        // source change (media source changes -> hasQueue becomes false -> queue is hidden), don't
        // save it.
        mCallbacks.setQueueVisible(updatedQueueVisibility);

        mMediaItemsRepository.getAnalyticsManager().sendViewChangedEvent(AnalyticsEvent.QUEUE_LIST,
                mQueueIsVisible ? AnalyticsEvent.SHOW : AnalyticsEvent.HIDE);
    }

    private void updateAppBarMenu(boolean hasQueue) {
        mQueueMenuItem.setVisible(hasQueue);
    }

    private void setQueueState(boolean hasQueue, boolean visible) {
        if (mHasQueue != hasQueue) {
            mHasQueue = hasQueue;
            updateAppBarMenu(hasQueue);
        }
        if (mQueueMenuItem != null) {
            mQueueMenuItem.setActivated(visible);
        }

        if (mQueueIsVisible != visible) {
            mQueueIsVisible = visible;
            if (mQueueIsVisible) {
                ViewUtils.showViewsAnimated(mViewsToShowWhenQueueIsVisible, mFadeDuration);
                ViewUtils.hideViewsAnimated(mViewsToHideWhenQueueIsVisible, mFadeDuration);
            } else {
                ViewUtils.hideViewsAnimated(mViewsToShowWhenQueueIsVisible, mFadeDuration);
                ViewUtils.showViewsAnimated(mViewsToHideWhenQueueIsVisible, mFadeDuration);
            }
            ViewUtils.setVisible(mViewsToShowImmediatelyWhenQueueIsVisible, mQueueIsVisible);
            ViewUtils.setVisible(mViewsToHideImmediatelyWhenQueueIsVisible, !mQueueIsVisible);
        }
    }

    private FragmentActivity getActivity() {
        return mCallbacks.getActivity();
    }

    /**
     * Collapses the playback controls.
     */
    public void closeOverflowMenu() {
        mPlaybackControls.close();
    }

    private void setSeekBarColor(int color) {
        mSeekBar.setProgressTintList(ColorStateList.valueOf(color));

        // If the thumb drawable consists of a center drawable, only change the color of the center
        // drawable. Otherwise change the color of the entire thumb drawable.
        Drawable thumb = mSeekBar.getThumb();
        if (thumb instanceof LayerDrawable) {
            LayerDrawable thumbDrawable = (LayerDrawable) thumb;
            Drawable thumbCenter = thumbDrawable.findDrawableByLayerId(R.id.thumb_center);
            if (thumbCenter != null) {
                thumbCenter.setColorFilter(color, PorterDuff.Mode.SRC);
                thumbDrawable.setDrawableByLayerId(R.id.thumb_center, thumbCenter);
                return;
            }
        }
        mSeekBar.setThumbTintList(ColorStateList.valueOf(color));
    }

    /**
     * Sets a listener of this PlaybackFragment events. In order to avoid memory leaks, consumers
     * must reset this reference by setting the listener to null.
     */
    public void setListener(NowPlayingListener listener) {
        mListener = listener;
    }
}
