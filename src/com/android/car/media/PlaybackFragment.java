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

import static android.car.media.CarMediaManager.MEDIA_SOURCE_MODE_PLAYBACK;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Preconditions;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProviders;

import com.android.car.apps.common.BackgroundImageView;
import com.android.car.apps.common.imaging.ImageBinder;
import com.android.car.apps.common.imaging.ImageBinder.PlaceholderType;
import com.android.car.apps.common.util.ViewUtils;
import com.android.car.media.PlaybackQueueFragment.PlaybackQueueCallback;
import com.android.car.media.common.ContentFormatView;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.MetadataController;
import com.android.car.media.common.PlaybackControlsActionBar;
import com.android.car.media.common.browse.MediaItemsRepository;
import com.android.car.media.common.playback.PlaybackViewModel;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.source.MediaSourceViewModel;
import com.android.car.media.widgets.AppBarController;
import com.android.car.ui.core.CarUi;
import com.android.car.ui.toolbar.MenuItem;
import com.android.car.ui.toolbar.NavButtonMode;
import com.android.car.ui.toolbar.ToolbarController;
import com.android.car.ui.utils.DirectManipulationHelper;
import com.android.car.uxr.LifeCycleObserverUxrContentLimiter;

import java.util.List;


/**
 * A {@link Fragment} that implements both the playback and the content forward browsing experience.
 * It observes a {@link PlaybackViewModel} and updates its information depending on the currently
 * playing media source through the {@link android.media.session.MediaSession} API.
 */
public class PlaybackFragment extends Fragment {
    private static final String TAG = "PlaybackFragment";

    private LifeCycleObserverUxrContentLimiter mUxrContentLimiter;
    private ImageBinder<MediaItemMetadata.ArtworkRef> mAlbumArtBinder;
    private AppBarController mAppBarController;
    private BackgroundImageView mAlbumBackground;
    private PlaybackQueueFragment mPlaybackQueueFragment;
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

    private PlaybackFragmentListener mListener;

    private boolean mHasQueue;
    private boolean mQueueIsVisible;

    private boolean mShowLinearProgressBar;

    private int mFadeDuration;

    private MediaActivity.ViewModel mViewModel;

    private MenuItem mQueueMenuItem;

    private PlaybackQueueFragment.PlaybackQueueCallback mPlaybackQueueCallback =
            new PlaybackQueueCallback() {
        @Override
        public void onQueueItemClicked(MediaItemMetadata item) {
            boolean switchToPlayback = getResources().getBoolean(
                    R.bool.switch_to_playback_view_when_playable_item_is_clicked);
            if (switchToPlayback) {
                toggleQueueVisibility();
            }
        }
    };

    /**
     * PlaybackFragment listener
     */
    public interface PlaybackFragmentListener {
        /**
         * Invoked when the user clicks on a browse link
         */
        void goToMediaItem(MediaSource source, MediaItemMetadata mediaItem);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, final ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_playback, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mPlaybackViewModel = PlaybackViewModel.get(getActivity().getApplication(),
                MEDIA_SOURCE_MODE_PLAYBACK);
        mMediaItemsRepository = MediaItemsRepository.get(getActivity().getApplication(),
                MEDIA_SOURCE_MODE_PLAYBACK);

        Resources res = getResources();
        mAlbumBackground = view.findViewById(R.id.playback_background);
        mPlaybackQueueFragment = new PlaybackQueueFragment();
        mPlaybackQueueFragment.setCallback(mPlaybackQueueCallback);

        getChildFragmentManager().beginTransaction()
            .add(R.id.queue_fragment_container, mPlaybackQueueFragment)
            .commit();

        mSeekBarContainer = view.findViewById(R.id.playback_seek_bar_container);
        mSeekBar = view.findViewById(R.id.playback_seek_bar);
        DirectManipulationHelper.setSupportsRotateDirectly(mSeekBar, true);

        GuidelinesUpdater updater = new GuidelinesUpdater(view);
        ToolbarController toolbarController = CarUi.installBaseLayoutAround(view, updater, true);
        mAppBarController = new AppBarController(view.getContext(), toolbarController,
                R.xml.menuitems_playback,
                res.getBoolean(R.bool.use_media_source_logo_for_app_selector_in_playback_view));

        mAppBarController.setTitle(R.string.fragment_playback_title);
        mAppBarController.setBackgroundShown(false);
        mAppBarController.setNavButtonMode(NavButtonMode.DOWN);

        mQueueMenuItem = mAppBarController.getMenuItem(R.id.menu_item_queue);
        Preconditions.checkNotNull(mQueueMenuItem);
        mQueueMenuItem.setOnClickListener((item) -> toggleQueueVisibility());

        // Update toolbar's logo
        MediaSourceViewModel mediaSourceViewModel = getMediaSourceViewModel();
        mediaSourceViewModel.getPrimaryMediaSource().observe(this, mediaSource ->
                mAppBarController.setLogo(mediaSource != null
                    ? new BitmapDrawable(getResources(), mediaSource.getCroppedPackageIcon())
                    : null));

        mBackgroundScrim = view.findViewById(R.id.background_scrim);
        ViewUtils.setVisible(mBackgroundScrim, false);
        mControlBarScrim = view.findViewById(R.id.control_bar_scrim);
        if (mControlBarScrim != null) {
            ViewUtils.setVisible(mControlBarScrim, false);
            mControlBarScrim.setOnClickListener(scrim -> mPlaybackControls.close());
            mControlBarScrim.setClickable(false);
        }

        mShowLinearProgressBar = getContext().getResources().getBoolean(
                R.bool.show_linear_progress_bar);

        if (mSeekBar != null) {
            if (mShowLinearProgressBar) {
                boolean useMediaSourceColor = res.getBoolean(
                        R.bool.use_media_source_color_for_progress_bar);
                int defaultColor = res.getColor(R.color.progress_bar_highlight, null);
                if (useMediaSourceColor) {
                    mPlaybackViewModel.getMediaSourceColors().observe(getViewLifecycleOwner(),
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

        mViewModel = ViewModelProviders.of(requireActivity()).get(MediaActivity.ViewModel.class);

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
                MediaAppConfig.getMediaItemsBitmapMaxSize(getContext()),
                drawable -> mAlbumBackground.setBackgroundDrawable(drawable));

        mPlaybackViewModel.getMetadata().observe(getViewLifecycleOwner(),
                item -> mAlbumArtBinder.setImage(PlaybackFragment.this.getContext(),
                        item != null ? item.getArtworkKey() : null));
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
        mPlaybackControls.setModel(mPlaybackViewModel, getViewLifecycleOwner());
        mPlaybackControls.registerExpandCollapseCallback((expanding) -> {
            Resources res = getContext().getResources();
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
        mFadeDuration = getResources().getInteger(
                R.integer.fragment_playback_queue_fade_duration_ms);

        // Make sure the AppBar menu reflects the initial state of playback fragment.
        updateAppBarMenu(mHasQueue);
        if (mQueueMenuItem != null) {
            mQueueMenuItem.setActivated(mQueueIsVisible);
        }

        mPlaybackViewModel.hasQueue().observe(getViewLifecycleOwner(),
                hasQueue -> {
                    boolean enableQueue = (hasQueue != null) && hasQueue;
                    boolean isQueueVisible = enableQueue && mViewModel.getQueueVisible();
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

        Size maxArtSize = MediaAppConfig.getMediaItemsBitmapMaxSize(view.getContext());
        mMetadataController = new MetadataController(getViewLifecycleOwner(), mPlaybackViewModel,
                mMediaItemsRepository, title, artist, albumTitle, outerSeparator, curTime,
                innerSeparator, maxTime, seekbar, albumArt, null, maxArtSize, contentFormat,
                (mediaItem) -> {
                    MediaSource source = mPlaybackViewModel.getMediaSource().getValue();
                    mListener.goToMediaItem(source, mediaItem);
                });
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
        mViewModel.setQueueVisible(updatedQueueVisibility);
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

    /**
     * Collapses the playback controls.
     */
    public void closeOverflowMenu() {
        mPlaybackControls.close();
    }

    private MediaSourceViewModel getMediaSourceViewModel() {
        return MediaSourceViewModel.get(getActivity().getApplication(), MEDIA_SOURCE_MODE_PLAYBACK);
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
    public void setListener(PlaybackFragmentListener listener) {
        mListener = listener;
    }
}
