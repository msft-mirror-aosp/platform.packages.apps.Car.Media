/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static androidx.lifecycle.Transformations.switchMap;

import static com.android.car.arch.common.LiveDataFunctions.distinct;
import static com.android.car.arch.common.LiveDataFunctions.nullLiveData;
import static com.android.car.arch.common.LiveDataFunctions.pair;

import android.app.AlertDialog;
import android.app.Application;
import android.app.PendingIntent;
import android.car.Car;
import android.car.drivingstate.CarUxRestrictions;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.transition.Fade;
import android.util.Log;
import android.util.Size;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProviders;

import com.android.car.apps.common.BackgroundImageView;
import com.android.car.apps.common.CarUxRestrictionsUtil;
import com.android.car.apps.common.util.ViewUtils;
import com.android.car.media.common.AppSelectionFragment;
import com.android.car.media.common.MediaAppSelectorWidget;
import com.android.car.media.common.MediaConstants;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.MinimizedPlaybackControlBar;
import com.android.car.media.common.browse.MediaBrowserViewModel;
import com.android.car.media.common.playback.AlbumArtLiveData;
import com.android.car.media.common.playback.PlaybackViewModel;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.source.MediaSourceViewModel;
import com.android.car.media.widgets.AppBarView;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * This activity controls the UI of media. It also updates the connection status for the media app
 * by broadcast.
 */
public class MediaActivity extends FragmentActivity implements BrowseFragment.Callbacks,
        AppBarView.AppBarProvider {
    private static final String TAG = "MediaActivity";

    /** Configuration (controlled from resources) */
    private int mFadeDuration;

    /** Models */
    private PlaybackViewModel.PlaybackController mPlaybackController;

    /** Layout views */
    private AppBarView mAppBarView;
    private BackgroundImageView mAlbumBackground;
    private PlaybackFragment mPlaybackFragment;
    private BrowseFragment mSearchFragment;
    private BrowseFragment mBrowseFragment;
    private AppSelectionFragment mAppSelectionFragment;
    private ViewGroup mMiniPlaybackControls;
    private EmptyFragment mEmptyFragment;
    private ViewGroup mBrowseContainer;
    private ViewGroup mPlaybackContainer;
    private ViewGroup mErrorContainer;
    private ErrorFragment mErrorFragment;
    private ViewGroup mSearchContainer;

    /** Current state */
    private Intent mCurrentSourcePreferences;
    private boolean mCanShowMiniPlaybackControls;
    private boolean mIsBrowseTreeReady;
    private Integer mCurrentPlaybackState;
    private List<MediaItemMetadata> mTopItems;

    private CarUxRestrictionsUtil mCarUxRestrictionsUtil;
    private CarUxRestrictions mActiveCarUxRestrictions;
    @CarUxRestrictions.CarUxRestrictionsInfo
    private int mRestrictions;
    private final CarUxRestrictionsUtil.OnUxRestrictionsChangedListener mListener =
            (carUxRestrictions) -> mActiveCarUxRestrictions = carUxRestrictions;

    private AppBarView.AppBarListener mAppBarListener = new AppBarView.AppBarListener() {
        @Override
        public void onTabSelected(MediaItemMetadata item) {
            showTopItem(item);
            getInnerViewModel().setMode(Mode.BROWSING);
        }

        @Override
        public void onBack() {
            BrowseFragment fragment = getCurrentBrowseFragment();
            if (fragment != null) {
                fragment.navigateBack();
            }
        }

        @Override
        public void onCollapse() {
            getInnerViewModel().setMode(Mode.BROWSING);
        }

        @Override
        public void onSettingsSelection() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onSettingsSelection");
            }
            try {
                if (mCurrentSourcePreferences != null) {
                    startActivity(mCurrentSourcePreferences);
                }
            } catch (ActivityNotFoundException e) {
                if (Log.isLoggable(TAG, Log.ERROR)) {
                    Log.e(TAG, "onSettingsSelection " + e);
                }
            }
        }

        @Override
        public void onSearchSelection() {
            getInnerViewModel().setMode(Mode.SEARCHING);
        }

        @Override
        public void onSearch(String query) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onSearch: " + query);
            }
            mSearchFragment.updateSearchQuery(query);
        }
    };

    private PlaybackFragment.PlaybackFragmentListener mPlaybackFragmentListener =
            () -> getInnerViewModel().setMode(Mode.BROWSING);

    /**
     * Possible modes of the application UI
     */
    private enum Mode {
        /** The user is browsing a media source */
        BROWSING,
        /** The user is interacting with the full screen playback UI */
        PLAYBACK,
        /** The user is searching within a media source */
        SEARCHING
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.media_activity);

        MediaSourceViewModel mediaSourceViewModel = getMediaSourceViewModel();
        PlaybackViewModel playbackViewModel = getPlaybackViewModel();
        ViewModel localViewModel = getInnerViewModel();
        // We can't rely on savedInstanceState to determine whether the model has been initialized
        // as on a config change savedInstanceState != null and the model is initialized, but if
        // the app was killed by the system then savedInstanceState != null and the model is NOT
        // initialized...
        if (localViewModel.needsInitialization()) {
            localViewModel.init(playbackViewModel);
            localViewModel.setMode(Mode.BROWSING);
        }

        mAppBarView = findViewById(R.id.app_bar);
        mAppBarView.setListener(mAppBarListener);
        mediaSourceViewModel.getPrimaryMediaSource().observe(this,
                this::onMediaSourceChanged);

        MediaAppSelectorWidget appSelector = findViewById(R.id.app_switch_container);
        appSelector.setFragmentActivity(this);

        mEmptyFragment = new EmptyFragment();
        MediaBrowserViewModel mediaBrowserViewModel = getRootBrowserViewModel();
        mediaBrowserViewModel.getBrowseState().observe(this,
                browseState -> {
                    mEmptyFragment.setState(browseState,
                            mediaSourceViewModel.getPrimaryMediaSource().getValue());
                });
        mediaBrowserViewModel.getBrowsedMediaItems().observe(this, futureData -> {
            if (!futureData.isLoading()) {
                if (futureData.getData() != null) {
                    mIsBrowseTreeReady = true;
                    handlePlaybackState(playbackViewModel.getPlaybackStateWrapper().getValue());
                }
                updateTabs(futureData.getData());
            }
        });
        mediaBrowserViewModel.supportsSearch().observe(this,
                mAppBarView::setSearchSupported);

        mPlaybackFragment = new PlaybackFragment();
        mPlaybackFragment.setListener(mPlaybackFragmentListener);
        mSearchFragment = BrowseFragment.newSearchInstance();
        mAppSelectionFragment = new AppSelectionFragment();
        int fadeDuration = getResources().getInteger(R.integer.app_selector_fade_duration);
        mAppSelectionFragment.setEnterTransition(new Fade().setDuration(fadeDuration));
        mAppSelectionFragment.setExitTransition(new Fade().setDuration(fadeDuration));
        mAlbumBackground = findViewById(R.id.playback_background);

        MinimizedPlaybackControlBar browsePlaybackControls =
                findViewById(R.id.minimized_playback_controls);
        browsePlaybackControls.setModel(playbackViewModel, this);

        mMiniPlaybackControls = findViewById(R.id.minimized_playback_controls);
        mMiniPlaybackControls.setOnClickListener(
                view -> getInnerViewModel().setMode(Mode.PLAYBACK));

        mFadeDuration = getResources().getInteger(
                R.integer.new_album_art_fade_in_duration);
        mBrowseContainer = findViewById(R.id.fragment_container);
        mErrorContainer = findViewById(R.id.error_container);
        mPlaybackContainer = findViewById(R.id.playback_container);
        mSearchContainer = findViewById(R.id.search_container);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.playback_container, mPlaybackFragment)
                .commit();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.search_container, mSearchFragment)
                .commit();

        playbackViewModel.getPlaybackController().observe(this,
                playbackController -> {
                    if (playbackController != null) playbackController.prepare();
                    mPlaybackController = playbackController;
                });

        mAlbumBackground.addOnLayoutChangeListener(
                (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    // This can be removed when fixing b/133523205. Keeping it prevents blurring
                    // until the playback view has been shown.
                    int backgroundImageSize = mAlbumBackground.getDesiredBackgroundSize();
                    localViewModel.setAlbumArtSize(backgroundImageSize, backgroundImageSize);
                });
        localViewModel.getAlbumArt().observe(this, this::setBackgroundImage);

        playbackViewModel.getPlaybackStateWrapper().observe(this, this::handlePlaybackState);

        localViewModel.getModeAndErrorState().observe(this, pair ->
                handleModeAndErrorState(pair.first, pair.second));

        mCarUxRestrictionsUtil = CarUxRestrictionsUtil.getInstance(this);
        mRestrictions = CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP;
        mCarUxRestrictionsUtil.register(mListener);
    }

    @Override
    protected void onDestroy() {
        mCarUxRestrictionsUtil.unregister(mListener);
        super.onDestroy();
    }

    private boolean isUxRestricted() {
        return CarUxRestrictionsUtil.isRestricted(mRestrictions, mActiveCarUxRestrictions);
    }

    private void handlePlaybackState(PlaybackViewModel.PlaybackStateWrapper state) {
        // TODO(arnaudberry) rethink interactions between customized layouts and dynamic visibility.
        mCanShowMiniPlaybackControls = (state != null) && state.shouldDisplay();

        // TODO(b/131252925) clean this up after Google IO.
        Pair<Mode, Boolean> modeState = getInnerViewModel().getModeAndErrorState().getValue();
        if (modeState == null || modeState.first != Mode.PLAYBACK) {
            ViewUtils.setVisible(mMiniPlaybackControls, mCanShowMiniPlaybackControls);
            getInnerViewModel().setMiniControlsVisible(mCanShowMiniPlaybackControls);
        }
        if (state == null) {
            return;
        }
        if (mCurrentPlaybackState == null || mCurrentPlaybackState != state.getState()) {
            mCurrentPlaybackState = state.getState();
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "handlePlaybackState(); state change: " + mCurrentPlaybackState);
            }
        }

        Bundle extras = state.getExtras();
        PendingIntent intent = extras == null ? null : extras.getParcelable(
                MediaConstants.ERROR_RESOLUTION_ACTION_INTENT);

        String label = extras == null ? null : extras.getString(
                MediaConstants.ERROR_RESOLUTION_ACTION_LABEL);

        String displayedMessage = null;
        if (!TextUtils.isEmpty(state.getErrorMessage())) {
            displayedMessage = state.getErrorMessage().toString();
        } else if (state.getErrorCode() != PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR) {
            // TODO(b/131601881): convert the error codes to prebuilt error messages
            displayedMessage = getString(R.string.default_error_message);
        } else if (state.getState() == PlaybackStateCompat.STATE_ERROR) {
            displayedMessage = getString(R.string.default_error_message);
        }

        boolean showErrorFragment = false;
        if (!TextUtils.isEmpty(displayedMessage)) {
            if (mIsBrowseTreeReady) {
                if (intent != null && !isUxRestricted()) {
                    showDialog(intent, displayedMessage, label, getString(android.R.string.cancel));
                } else {
                    showToast(displayedMessage);
                }
            } else {
                mErrorFragment = ErrorFragment.newInstance(displayedMessage, label, intent);
                setErrorFragment(mErrorFragment);
                showErrorFragment = true;
            }
        }
        getInnerViewModel().setErrorState(showErrorFragment);
    }

    private void showDialog(PendingIntent intent, String message, String positiveBtnText,
            String negativeButtonText) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this);
        dialog.setMessage(message)
                .setNegativeButton(negativeButtonText, null)
                .setPositiveButton(positiveBtnText, (dialogInterface, i) -> {
                    try {
                        intent.send();
                    } catch (PendingIntent.CanceledException e) {
                        if (Log.isLoggable(TAG, Log.ERROR)) {
                            Log.e(TAG, "Pending intent canceled");
                        }
                    }
                })
                .show();
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();

        MediaSource mediaSource = getMediaSourceViewModel().getPrimaryMediaSource().getValue();
        if (mediaSource == null) {
            mAppBarView.openAppSelector();
        } else {
            mAppBarView.closeAppSelector();
        }
    }

    /**
     * Sets the media source being browsed.
     *
     * @param mediaSource the new media source we are going to try to browse
     */
    private void onMediaSourceChanged(@Nullable MediaSource mediaSource) {
        mIsBrowseTreeReady = false;
        if (mediaSource != null) {
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "Browsing: " + mediaSource.getName());
            }
            mAppBarView.setMediaAppName(mediaSource.getName());
            mAppBarView.setTitle(null);
            updateTabs(null);
            mSearchFragment.resetSearchState();
            getInnerViewModel().setMode(Mode.BROWSING);
            getInnerViewModel().setErrorState(false);
            String packageName = mediaSource.getPackageName();
            updateSourcePreferences(packageName);

            // Always go through the trampoline activity to keep all the dispatching logic there.
            startActivity(new Intent(Car.CAR_INTENT_ACTION_MEDIA_TEMPLATE));
        } else {
            mAppBarView.setMediaAppName("");
            mAppBarView.setTitle(null);
            updateTabs(null);
            updateSourcePreferences(null);
        }
    }

    private void updateSourcePreferences(@Nullable String packageName) {
        mCurrentSourcePreferences = null;
        if (packageName != null) {
            Intent prefsIntent = new Intent(Intent.ACTION_APPLICATION_PREFERENCES);
            prefsIntent.setPackage(packageName);
            ResolveInfo info = getPackageManager().resolveActivity(prefsIntent, 0);
            if (info != null) {
                mCurrentSourcePreferences = new Intent(prefsIntent.getAction())
                        .setClassName(info.activityInfo.packageName, info.activityInfo.name);
            }
        }
        mAppBarView.setHasSettings(mCurrentSourcePreferences != null);
    }

    /**
     * Updates the tabs displayed on the app bar, based on the top level items on the browse tree.
     * If there is at least one browsable item, we show the browse content of that node. If there
     * are only playable items, then we show those items. If there are not items at all, we show the
     * empty message. If we receive null, we show the error message.
     *
     * @param items top level items, or null if there was an error trying load those items.
     */
    private void updateTabs(List<MediaItemMetadata> items) {
        if (items == null || items.isEmpty()) {
            mAppBarView.setActiveItem(null);
            mAppBarView.setItems(null);
            setCurrentFragment(mEmptyFragment);
            mBrowseFragment = null;
            mTopItems = items;
            return;
        }

        if (Objects.equals(mTopItems, items)) {
            // When coming back to the app, the live data sends an update even if the list hasn't
            // changed. Updating the tabs then recreates the browse fragment, which produces jank
            // (b/131830876), and also resets the navigation to the top of the first tab...
            return;
        }
        mTopItems = items;

        List<MediaItemMetadata> browsableTopLevel = items.stream()
                .filter(MediaItemMetadata::isBrowsable)
                .collect(Collectors.toList());

        mAppBarView.setItems(browsableTopLevel);
        showTopItem(browsableTopLevel.isEmpty() ? null : browsableTopLevel.get(0));
    }

    private void showTopItem(@Nullable MediaItemMetadata topItem) {
        mBrowseFragment = BrowseFragment.newInstance(topItem);
        setCurrentFragment(mBrowseFragment);
        mAppBarView.setActiveItem(topItem);
    }

    private void setErrorFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.error_container, fragment)
                .commitAllowingStateLoss();
    }

    private void setCurrentFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commitAllowingStateLoss();
    }

    @Nullable
    private BrowseFragment getCurrentBrowseFragment() {
        return getInnerViewModel().mMode.getValue() == Mode.SEARCHING
                ? mSearchFragment
                : mBrowseFragment;
    }

    private void handleModeAndErrorState(Mode mode, Boolean isErrorMode) {
        if (Log.isLoggable(TAG, Log.INFO)) {
            Log.i(TAG, "switchToMode(); mode: " + mode + " errorState: " + isErrorMode);
        }

        if (isErrorMode) {
            ViewUtils.showViewAnimated(mErrorContainer, mFadeDuration);
            ViewUtils.hideViewAnimated(mPlaybackContainer, mFadeDuration);
            ViewUtils.hideViewAnimated(mBrowseContainer, mFadeDuration);
            ViewUtils.hideViewAnimated(mSearchContainer, mFadeDuration);
            mAppBarView.setState(AppBarView.State.EMPTY);
            return;
        }

        updateMetadata(mode);

        switch (mode) {
            case PLAYBACK:
                ViewUtils.hideViewAnimated(mErrorContainer, mFadeDuration);
                ViewUtils.showViewAnimated(mPlaybackContainer, mFadeDuration);
                ViewUtils.hideViewAnimated(mBrowseContainer, mFadeDuration);
                ViewUtils.hideViewAnimated(mSearchContainer, mFadeDuration);
                ViewUtils.hideViewAnimated(mAppBarView, mFadeDuration);
                mAppBarView.setState(AppBarView.State.PLAYING);
                break;
            case BROWSING:
                ViewUtils.hideViewAnimated(mErrorContainer, mFadeDuration);
                ViewUtils.hideViewAnimated(mPlaybackContainer, mFadeDuration);
                ViewUtils.showViewAnimated(mBrowseContainer, mFadeDuration);
                ViewUtils.hideViewAnimated(mSearchContainer, mFadeDuration);
                ViewUtils.showViewAnimated(mAppBarView, mFadeDuration);
                updateAppBar(mode);
                break;
            case SEARCHING:
                ViewUtils.hideViewAnimated(mErrorContainer, mFadeDuration);
                ViewUtils.hideViewAnimated(mPlaybackContainer, mFadeDuration);
                ViewUtils.hideViewAnimated(mBrowseContainer, mFadeDuration);
                ViewUtils.showViewAnimated(mSearchContainer, mFadeDuration);
                ViewUtils.showViewAnimated(mAppBarView, mFadeDuration);
                updateAppBar(mode);
                break;
        }
    }

    private void updateAppBar(Mode mode) {
        BrowseFragment fragment = getCurrentBrowseFragment();
        boolean isStacked = fragment != null && !fragment.isAtTopStack();
        AppBarView.State unstackedState = mode == Mode.SEARCHING
                ? AppBarView.State.SEARCHING
                : AppBarView.State.BROWSING;
        mAppBarView.setTitle(isStacked ? fragment.getCurrentMediaItem().getTitle() : null);
        mAppBarView.setState(isStacked ? AppBarView.State.STACKED : unstackedState);
    }

    private void updateMetadata(Mode mode) {
        if (mode == Mode.PLAYBACK) {
            ViewUtils.hideViewAnimated(mMiniPlaybackControls, mFadeDuration);
            ViewUtils.showViewAnimated(mAlbumBackground, mFadeDuration);
            getInnerViewModel().setMiniControlsVisible(false);
        } else {
            mPlaybackFragment.closeOverflowMenu();
            if (mCanShowMiniPlaybackControls) {
                ViewUtils.showViewAnimated(mMiniPlaybackControls, mFadeDuration);
                getInnerViewModel().setMiniControlsVisible(true);
            }
            ViewUtils.hideViewAnimated(mAlbumBackground, mFadeDuration);
        }
    }

    private void setBackgroundImage(Bitmap bitmap) {
        mAlbumBackground.setBackgroundImage(bitmap, bitmap != null);
    }

    @Override
    public void onBackStackChanged() {
        updateAppBar(getInnerViewModel().mMode.getValue());
    }

    @Override
    public void onPlayableItemClicked(MediaItemMetadata item) {
        mPlaybackController.stop();
        mPlaybackController.playItem(item.getId());
        boolean switchToPlayback = getResources().getBoolean(
                R.bool.switch_to_playback_view_when_playable_item_is_clicked);
        if (switchToPlayback) {
            getInnerViewModel().setMode(Mode.PLAYBACK);
        } else if (getInnerViewModel().mMode.getValue() == Mode.SEARCHING) {
            getInnerViewModel().setMode(Mode.BROWSING);
        }
        setIntent(null);
    }

    public MediaSourceViewModel getMediaSourceViewModel() {
        return MediaSourceViewModel.get(getApplication());
    }

    public PlaybackViewModel getPlaybackViewModel() {
        return PlaybackViewModel.get(getApplication());
    }

    private MediaBrowserViewModel getRootBrowserViewModel() {
        return MediaBrowserViewModel.Factory.getInstanceForBrowseRoot(getMediaSourceViewModel(),
                ViewModelProviders.of(this));
    }

    public ViewModel getInnerViewModel() {
        return ViewModelProviders.of(this).get(ViewModel.class);
    }

    @Override
    public AppBarView getAppBar() {
        return mAppBarView;
    }

    public static class ViewModel extends AndroidViewModel {
        private boolean mNeedsInitialization = true;
        private LiveData<Bitmap> mAlbumArt;
        private MutableLiveData<Size> mAlbumArtSize = new MutableLiveData<>();
        private PlaybackViewModel mPlaybackViewModel;
        private MutableLiveData<Boolean> mIsMiniControlsVisible = new MutableLiveData<>();

        private MutableLiveData<Boolean> mIsErrorState = new MutableLiveData<>();
        private MutableLiveData<Mode> mMode = new MutableLiveData<>();
        private LiveData<Pair<Mode, Boolean>> mModeAndErrorState = pair(mMode, mIsErrorState);

        public ViewModel(@NonNull Application application) {
            super(application);
        }

        void init(@NonNull PlaybackViewModel playbackViewModel) {
            if (mPlaybackViewModel == playbackViewModel) {
                return;
            }
            mPlaybackViewModel = playbackViewModel;

            mAlbumArt = switchMap(distinct(mAlbumArtSize), size -> {
                if (size == null || size.getHeight() == 0 || size.getWidth() == 0) {
                    return nullLiveData();
                } else {
                    return AlbumArtLiveData.getAlbumArt(getApplication(),
                            size.getWidth(), size.getHeight(), false,
                            playbackViewModel.getMetadata());
                }
            });

            mIsErrorState.setValue(false);
            mNeedsInitialization = false;
        }

        boolean needsInitialization() {
            return mNeedsInitialization;
        }

        void setAlbumArtSize(int width, int height) {
            mAlbumArtSize.setValue(new Size(width, height));
        }

        LiveData<Bitmap> getAlbumArt() {
            return mAlbumArt;
        }

        void setMiniControlsVisible(boolean visible) {
            mIsMiniControlsVisible.setValue(visible);
        }

        LiveData<Boolean> getMiniControlsVisible() {
            return mIsMiniControlsVisible;
        }

        void setMode(Mode mode) {
            mMode.setValue(mode);
        }

        LiveData<Pair<Mode, Boolean>> getModeAndErrorState() {
            return mModeAndErrorState;
        }

        void setErrorState(boolean state) {
            mIsErrorState.setValue(state);
        }
    }
}
