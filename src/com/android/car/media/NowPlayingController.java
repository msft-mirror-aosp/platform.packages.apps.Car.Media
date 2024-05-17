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

import static androidx.car.app.mediaextensions.analytics.event.AnalyticsEvent.VIEW_ACTION_HIDE;
import static androidx.car.app.mediaextensions.analytics.event.AnalyticsEvent.VIEW_ACTION_SHOW;
import static androidx.car.app.mediaextensions.analytics.event.AnalyticsEvent.VIEW_COMPONENT_PLAYBACK;
import static androidx.car.app.mediaextensions.analytics.event.AnalyticsEvent.VIEW_COMPONENT_QUEUE_LIST;

import static com.android.car.apps.common.util.LiveDataFunctions.combine;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.car.app.mediaextensions.analytics.event.AnalyticsEvent;
import androidx.core.util.Preconditions;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.android.car.apps.common.BackgroundImageView;
import com.android.car.apps.common.TappableTextView;
import com.android.car.apps.common.imaging.ImageBinder;
import com.android.car.apps.common.util.ViewUtils;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.MediaLinkHandler;
import com.android.car.media.common.PlaybackControlsActionBar;
import com.android.car.media.common.playback.PlaybackProgress;
import com.android.car.media.common.playback.PlaybackViewModel;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.source.MediaSourceColors;
import com.android.car.media.common.ui.PlaybackCardController;
import com.android.car.media.common.ui.PlaybackQueueController;
import com.android.car.media.common.ui.PlaybackQueueController.PlaybackQueueCallback;
import com.android.car.media.common.ui.UxrPivotFilterImpl;
import com.android.car.media.widgets.AppBarController;
import com.android.car.ui.core.CarUi;
import com.android.car.ui.toolbar.MenuItem;
import com.android.car.ui.toolbar.NavButtonMode;
import com.android.car.ui.toolbar.ToolbarController;
import com.android.car.ui.utils.DirectManipulationHelper;
import com.android.car.uxr.CarUxRestrictionsAppConfig;
import com.android.car.uxr.LifeCycleObserverUxrContentLimiter;
import com.android.car.uxr.UxrContentLimiterImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Fragment} that implements both the playback and the content forward browsing experience.
 * It observes a {@link PlaybackViewModel} and updates its information depending on the currently
 * playing media source through the {@link android.media.session.MediaSession} API.
 */
@OptIn(markerClass = androidx.car.app.annotations2.ExperimentalCarApi.class)
public class NowPlayingController extends PlaybackCardController {
    private static final String TAG = "NowPlayingController";

    private final FragmentActivity mActivity;
    private ImageBinder<MediaItemMetadata.ArtworkRef> mAlbumBackgroundArtBinder;
    private AppBarController mAppBarController;
    private BackgroundImageView mAlbumBackground;
    private PlaybackQueueController mPlaybackQueueController;
    private View mBackgroundScrim;
    private View mControlBarScrim;
    private View mLogoSeparatorView;
    private View mViewSeparatedFromLogo;
    private PlaybackControlsActionBar mPlaybackControls;
    private ViewGroup mSeekBarContainer;
    private List<View> mViewsToHideForCustomActions;
    private List<View> mViewsToHideWhenQueueIsVisible;
    private List<View> mViewsToShowWhenQueueIsVisible;
    private List<View> mViewsToHideImmediatelyWhenQueueIsVisible;
    private List<View> mViewsToShowImmediatelyWhenQueueIsVisible;

    private NowPlayingListener mListener;

    private boolean mShowLinearProgressBar;

    private int mFadeDuration;

    private MenuItem mQueueMenuItem;

    private PlaybackQueueCallback mPlaybackQueueCallback =
            new PlaybackQueueCallback() {
        @Override
        public void onQueueItemClicked(MediaItemMetadata item) {
            boolean switchToPlayback = getActivity().getResources().getBoolean(
                    R.bool.switch_to_playback_view_when_playable_item_is_clicked);
            if (item.getId() != null) {
                //Send analytics click event
                mItemsRepository.getAnalyticsManager()
                        .sendMediaClickedEvent(item.getId(), VIEW_COMPONENT_QUEUE_LIST);
            }
            if (switchToPlayback) {
                toggleQueueVisibility();
            }
        }
    };

    /** Builder for {@link NowPlayingController}. Overrides build() method to return
     * NowPlayingController rather than base {@link PlaybackCardController}
     */
    public static class Builder extends PlaybackCardController.Builder {

        @Override
        public NowPlayingController build() {
            NowPlayingController npc = new NowPlayingController(this);
            npc.setupController();
            return npc;
        }
    }

    public NowPlayingController(Builder builder) {
        super(builder);
        mActivity = (FragmentActivity) getViewLifecycleOwner();

        Resources res = mView.getContext().getResources();
        mAlbumBackground = mView.findViewById(R.id.playback_background);
        mLogoSeparatorView = mView.findViewById(R.id.logo_separator);
        mViewSeparatedFromLogo = mView.findViewWithTag("view_separated_from_logo");
        TextView outerSeparator = mView.findViewById(R.id.outer_separator);
        if (outerSeparator != null) {
            combine(mDataModel.getMetadata(), mDataModel.getProgress(),
                    (metadata, progress) -> metadata != null
                            && !TextUtils.isEmpty(metadata.getDescription()) && progress.hasTime())
                    .observe(getActivity(),
                            visible -> ViewUtils.setVisible(outerSeparator, visible));
        }

        ViewGroup queueContainer = mView.findViewById(R.id.queue_fragment_container);
        mPlaybackQueueController = new PlaybackQueueController(
                queueContainer, R.layout.fragment_playback_queue, R.layout.queue_list_item,
                Resources.ID_NULL, mActivity, mDataModel, mItemsRepository,
                new LifeCycleObserverUxrContentLimiter(
                        new UxrContentLimiterImpl(mView.getContext(), R.xml.uxr_config)),
                R.id.playback_fragment_now_playing_list_uxr_config);
        mPlaybackQueueController.setCallback(mPlaybackQueueCallback);
        mPlaybackQueueController.setShowTimeForActiveQueueItem(res.getBoolean(
                R.bool.show_time_for_now_playing_queue_list_item));
        mPlaybackQueueController.setShowIconForActiveQueueItem(res.getBoolean(
                R.bool.show_icon_for_now_playing_queue_list_item));
        mPlaybackQueueController.setShowThumbnailForQueueItem(res.getBoolean(
                R.bool.show_thumbnail_for_queue_list_item));
        mPlaybackQueueController.setShowSubtitleForQueueItem(res.getBoolean(
                R.bool.show_subtitle_for_queue_list_item));
        mPlaybackQueueController.setVerticalFadingEdgeLengthEnabled(res.getBoolean(
                R.bool.queue_fading_edge_length_enabled));

        mSeekBarContainer = mView.findViewById(R.id.playback_seek_bar_container);

        GuidelinesUpdater updater = new GuidelinesUpdater(mView);
        ToolbarController toolbarController = CarUi.installBaseLayoutAround(mView, updater, true);
        mAppBarController = new AppBarController(mView.getContext(), mItemsRepository,
                toolbarController, R.xml.menuitems_playback,
                res.getBoolean(R.bool.use_media_source_logo_for_app_selector_in_playback_view));

        mAppBarController.setTitle(R.string.fragment_playback_title);
        mAppBarController.setBackgroundShown(false);
        mAppBarController.setNavButtonMode(NavButtonMode.DOWN);

        mQueueMenuItem = mAppBarController.getMenuItem(R.id.menu_item_queue);
        Preconditions.checkNotNull(mQueueMenuItem);
        mQueueMenuItem.setOnClickListener((item) -> toggleQueueVisibility());

        mBackgroundScrim = mView.findViewById(R.id.background_scrim);
        ViewUtils.setVisible(mBackgroundScrim, false);
        mControlBarScrim = mView.findViewById(R.id.control_bar_scrim);
        if (mControlBarScrim != null) {
            ViewUtils.setVisible(mControlBarScrim, false);
            mControlBarScrim.setOnClickListener(scrim -> mPlaybackControls.close());
            mControlBarScrim.setClickable(false);
        }

        mShowLinearProgressBar = mView.getContext().getResources().getBoolean(
                R.bool.show_linear_progress_bar);

        initPlaybackControls(mView.findViewById(R.id.playback_controls));

        // Don't update the visibility of seekBar if show_linear_progress_bar is false.
        ViewUtils.Filter ignoreSeekBarFilter =
                (viewToFilter) -> mShowLinearProgressBar || viewToFilter != mSeekBarContainer;

        mViewsToHideForCustomActions = ViewUtils.getViewsById(mView, res,
                R.array.playback_views_to_hide_when_showing_custom_actions, ignoreSeekBarFilter);
        mViewsToHideWhenQueueIsVisible = ViewUtils.getViewsById(mView, res,
                R.array.playback_views_to_hide_when_queue_is_visible, ignoreSeekBarFilter);
        mViewsToShowWhenQueueIsVisible = ViewUtils.getViewsById(mView, res,
                R.array.playback_views_to_show_when_queue_is_visible, null);
        mViewsToHideImmediatelyWhenQueueIsVisible = ViewUtils.getViewsById(mView, res,
                R.array.playback_views_to_hide_immediately_when_queue_is_visible,
                ignoreSeekBarFilter);
        mViewsToShowImmediatelyWhenQueueIsVisible = ViewUtils.getViewsById(mView, res,
                R.array.playback_views_to_show_immediately_when_queue_is_visible, null);

        mAlbumBackgroundArtBinder = new ImageBinder<>(
                ImageBinder.PlaceholderType.BACKGROUND,
                MediaAppConfig.getMediaItemsBitmapMaxSize(mView.getContext()),
                drawable -> mAlbumBackground.setBackgroundDrawable(drawable));
    }

    /**
     * NowPlayingController listener
     */
    public interface NowPlayingListener {
        /**
         * Invoked when the user clicks on a browse link
         */
        void goToMediaItem(MediaSource source, MediaItemMetadata mediaItem);
    }

    /** Returns the maximum number of items in the queue under driving restrictions. */
    public static int getMaxItemsWhileRestricted(Context context) {
        Integer maxItems = CarUxRestrictionsAppConfig.getContentLimit(context,
                R.xml.uxr_config, R.id.playback_fragment_now_playing_list_uxr_config);
        Preconditions.checkNotNull(maxItems, "Misconfigured list limits.");
        return (maxItems <= 0) ? -1 : UxrPivotFilterImpl.adjustMaxItems(maxItems);
    }

    /**
     * Tells the controller what is actually happening to its view, so that it can be
     * considered hidden right when a hiding animation starts.
     */
    public void onActualVisibilityChanged(boolean isShown) {
        mPlaybackQueueController.onActualVisibilityChanged(isShown
                && mViewModel.getQueueVisible());
    }

    private void initPlaybackControls(PlaybackControlsActionBar playbackControls) {
        mPlaybackControls = playbackControls;
        mPlaybackControls.setModel(mDataModel, getActivity());
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

            if (!mViewModel.getQueueVisible()) {
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
        updateAppBarMenu(getMediaHasQueue());
        if (mQueueMenuItem != null) {
            mQueueMenuItem.setActivated(mViewModel.getQueueVisible());
        }
    }

    /**
     * Hides or shows the playback queue when the user clicks the queue button.
     */
    private void toggleQueueVisibility() {
        boolean updatedQueueVisibility = !mViewModel.getQueueVisible();
        setQueueState(getMediaHasQueue(), updatedQueueVisibility);

        // When the visibility of queue is changed by the user, save the visibility into ViewModel
        // so that we can restore PlaybackFragment properly when needed. If it's changed by media
        // source change (media source changes -> hasQueue becomes false -> queue is hidden), don't
        // save it.
        mViewModel.setQueueVisible(updatedQueueVisibility);

        mItemsRepository.getAnalyticsManager().sendViewChangedEvent(VIEW_COMPONENT_QUEUE_LIST,
                mViewModel.getQueueVisible() ? VIEW_ACTION_SHOW : VIEW_ACTION_HIDE);
    }

    private void updateAppBarMenu(boolean hasQueue) {
        mQueueMenuItem.setVisible(hasQueue);
    }

    @Override
    protected void updateQueueState(boolean hasQueue, boolean isQueueVisible) {
        setQueueState(hasQueue, isQueueVisible);
    }

    private void setQueueState(boolean hasQueue, boolean visible) {
        updateAppBarMenu(hasQueue);

        if (mQueueMenuItem != null) {
            mQueueMenuItem.setActivated(visible);
        }

        if (mViewModel.getQueueVisible() != visible) {
            mViewModel.setQueueVisible(visible);
            mPlaybackQueueController.onActualVisibilityChanged(mViewModel.getQueueVisible());
        }
        if (mViewModel.getQueueVisible()) {
            ViewUtils.showViewsAnimated(mViewsToShowWhenQueueIsVisible, mFadeDuration);
            ViewUtils.hideViewsAnimated(mViewsToHideWhenQueueIsVisible, mFadeDuration);
        } else {
            ViewUtils.hideViewsAnimated(mViewsToShowWhenQueueIsVisible, mFadeDuration);
            ViewUtils.showViewsAnimated(mViewsToHideWhenQueueIsVisible, mFadeDuration);
        }
        ViewUtils.setVisible(mViewsToShowImmediatelyWhenQueueIsVisible,
                mViewModel.getQueueVisible());
        ViewUtils.setVisible(mViewsToHideImmediatelyWhenQueueIsVisible,
                !mViewModel.getQueueVisible());
    }

    private FragmentActivity getActivity() {
        return mActivity;
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

    private void setUpMetadataLinkers() {
        MediaLinkHandler.MediaLinkDelegate delegate = (mediaItem) -> {
            mItemsRepository.getAnalyticsManager()
                    .sendMediaClickedEvent(mediaItem.getId(), VIEW_COMPONENT_PLAYBACK);
            MediaSource source = mDataModel.getMediaSource().getValue();
            mListener.goToMediaItem(source, mediaItem);
        };
        mSubtitleLinker = new MediaLinkHandler(mItemsRepository, delegate, mSubtitle);
        mDescriptionLinker = new MediaLinkHandler(mItemsRepository, delegate, mDescription);
    }

    @Override
    protected void setupController() {
        super.setupController();
        initQueue();
        setUpMetadataLinkers();
    }

    @Override
    protected void setUpSeekBar() {
        super.setUpSeekBar();
        DirectManipulationHelper.setSupportsRotateDirectly(mSeekBar, true);
        ViewUtils.setVisible(mSeekBar, mShowLinearProgressBar);
    }

    @Override
    protected void updateAlbumCoverWithDrawable(Drawable drawable) {
        super.updateAlbumCoverWithDrawable(drawable);
        mAlbumBackground.setBackgroundDrawable(drawable);
    }

    @Override
    protected void updateMetadata(MediaItemMetadata metadata) {
        super.updateMetadata(metadata);
        mAlbumBackgroundArtBinder.setImage(mView.getContext(), metadata != null
                ? metadata.getArtworkKey() : null);
        if (metadata != null && mView.isShown()) {
            ArrayList<String> items = new ArrayList<>();
            items.add(metadata.getId());
            mItemsRepository.getAnalyticsManager().sendVisibleItemsEvents(null,
                    VIEW_COMPONENT_PLAYBACK, VIEW_ACTION_SHOW,
                    AnalyticsEvent.VIEW_ACTION_MODE_NONE, items);
        }
        if (!ViewUtils.isVisible(mDescription) && mDataModel.getProgress().getValue() != null
                && mDataModel.getProgress().getValue().hasTime()) {
            if (mDescription instanceof TappableTextView) {
                ((TappableTextView) mDescription).hideView(true);
            } else {
                // In layout file, subtitle is constrained to description. When
                // album name is empty but progress is not empty, the visibility of
                // description should be INVISIBLE instead of GONE, otherwise the
                // constraint will be broken.
                ViewUtils.setInvisible(mDescription, true);
            }
        }
        ViewUtils.setVisible(mLogoSeparatorView, (ViewUtils.isVisible(mLogo)
                && ViewUtils.isVisible(mViewSeparatedFromLogo)));
    }

    @Override
    protected void updateProgress(PlaybackProgress progress) {
        super.updateProgress(progress);
        ViewUtils.setVisible(mLogoSeparatorView, (ViewUtils.isVisible(mLogo)
                && ViewUtils.isVisible(mViewSeparatedFromLogo)));
        ViewUtils.setInvisible(mSeekBar, (progress == null || !progress.hasTime()));
    }

    @Override
    protected void updatePlaybackState(PlaybackViewModel.PlaybackStateWrapper playbackState) {
        super.updatePlaybackState(playbackState);
        if (mSeekBar != null) {
            boolean enabled = playbackState != null && playbackState.isSeekToEnabled();
            mTrackingTouch = false;
            if (mSeekBar.getThumb() != null) {
                mSeekBar.getThumb().mutate().setAlpha(enabled ? 255 : 0);
            }
            final boolean shouldHandleTouch = mSeekBar.getThumb() != null && enabled;
            mSeekBar.setOnTouchListener(
                    (v, event) -> !shouldHandleTouch /* consumeEvent */);
        }
    }

    @Override
    protected void updateMediaSource(MediaSource mediaSource) {
        mAppBarController.setLogo(mediaSource != null
                ? new BitmapDrawable(
                mView.getContext().getResources(), mediaSource.getCroppedPackageIcon())
                : null);
    }

    @Override
    protected void updateViewsWithMediaSourceColors(MediaSourceColors colors) {
        super.updateViewsWithMediaSourceColors(colors);
        Resources res = mView.getContext().getResources();
        boolean useMediaSourceColor = res.getBoolean(
                R.bool.use_media_source_color_for_progress_bar);
        int defaultColor = res.getColor(R.color.progress_bar_highlight, null);

        int color = useMediaSourceColor && colors != null
                ? colors.getAccentColor(defaultColor)
                : defaultColor;
        setSeekBarColor(color);
    }
}
