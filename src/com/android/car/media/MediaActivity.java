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

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Application;
import android.app.PendingIntent;
import android.car.Car;
import android.car.drivingstate.CarUxRestrictions;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GestureDetectorCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProviders;

import com.android.car.apps.common.CarUxRestrictionsUtil;
import com.android.car.apps.common.util.ViewUtils;
import com.android.car.media.common.MediaConstants;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.MinimizedPlaybackControlBar;
import com.android.car.media.common.browse.MediaBrowserViewModel;
import com.android.car.media.common.playback.PlaybackViewModel;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.source.MediaSourceViewModel;
import com.android.car.media.widgets.AppBarView;
import com.android.car.ui.toolbar.Toolbar;
import com.android.car.ui.AlertDialogBuilder;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * This activity controls the UI of media. It also updates the connection status for the media app
 * by broadcast.
 */
public class MediaActivity extends FragmentActivity implements BrowseFragment.Callbacks {
    private static final String TAG = "MediaActivity";

    /** Configuration (controlled from resources) */
    private int mFadeDuration;

    /** Models */
    private PlaybackViewModel.PlaybackController mPlaybackController;

    /** Layout views */
    private View mRootView;
    private AppBarView mAppBarView;
    private PlaybackFragment mPlaybackFragment;
    private BrowseFragment mSearchFragment;
    private BrowseFragment mBrowseFragment;
    private MinimizedPlaybackControlBar mMiniPlaybackControls;
    private EmptyFragment mEmptyFragment;
    private ViewGroup mBrowseContainer;
    private ViewGroup mPlaybackContainer;
    private ViewGroup mErrorContainer;
    private ErrorFragment mErrorFragment;
    private ViewGroup mSearchContainer;

    private Toast mToast;
    private AlertDialog mDialog;

    /** Current state */
    private Mode mMode;
    private Intent mCurrentSourcePreferences;
    private boolean mCanShowMiniPlaybackControls;
    private boolean mBrowseTreeHasChildren;
    private PlaybackViewModel.PlaybackStateWrapper mCurrentPlaybackStateWrapper;
    private List<MediaItemMetadata> mTopItems;

    private CarUxRestrictionsUtil mCarUxRestrictionsUtil;
    private CarUxRestrictions mActiveCarUxRestrictions;
    @CarUxRestrictions.CarUxRestrictionsInfo
    private int mRestrictions;
    private final CarUxRestrictionsUtil.OnUxRestrictionsChangedListener mListener =
            (carUxRestrictions) -> mActiveCarUxRestrictions = carUxRestrictions;
    private Intent mAppSelectorIntent;

    private AppBarView.AppBarListener mAppBarListener = new AppBarView.AppBarListener() {
        @Override
        public void onTabSelected(MediaItemMetadata item) {
            showTopItem(item);
            changeMode(Mode.BROWSING);
        }

        @Override
        public void onBack() {
            BrowseFragment fragment = getCurrentBrowseFragment();
            if (fragment != null) {
                boolean success = fragment.navigateBack();
                if (!success && (fragment == mSearchFragment)) {
                    changeMode(Mode.BROWSING);
                }
            }
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
            changeMode(Mode.SEARCHING);
        }

        @Override
        public void onAppSwitch() {
            MediaActivity.this.startActivity(mAppSelectorIntent);
        }

        @Override
        public void onHeightChanged(int height) {
            BrowseFragment fragment = getCurrentBrowseFragment();
            if (fragment != null) {
                fragment.onAppBarHeightChanged(height);
            }
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
            () -> changeMode(Mode.BROWSING);

    /**
     * Possible modes of the application UI
     */
    private enum Mode {
        /** The user is browsing a media source */
        BROWSING,
        /** The user is interacting with the full screen playback UI */
        PLAYBACK,
        /** The user is searching within a media source */
        SEARCHING,
        /** There's no browse tree and playback doesn't work. */
        FATAL_ERROR
    }

    private static final Map<Integer, Integer> ERROR_CODE_MESSAGES_MAP;

    static {
        Map<Integer, Integer> map = new HashMap<>();
        map.put(PlaybackStateCompat.ERROR_CODE_APP_ERROR, R.string.error_code_app_error);
        map.put(PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED, R.string.error_code_not_supported);
        map.put(PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED,
                R.string.error_code_authentication_expired);
        map.put(PlaybackStateCompat.ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED,
                R.string.error_code_premium_account_required);
        map.put(PlaybackStateCompat.ERROR_CODE_CONCURRENT_STREAM_LIMIT,
                R.string.error_code_concurrent_stream_limit);
        map.put(PlaybackStateCompat.ERROR_CODE_PARENTAL_CONTROL_RESTRICTED,
                R.string.error_code_parental_control_restricted);
        map.put(PlaybackStateCompat.ERROR_CODE_NOT_AVAILABLE_IN_REGION,
                R.string.error_code_not_available_in_region);
        map.put(PlaybackStateCompat.ERROR_CODE_CONTENT_ALREADY_PLAYING,
                R.string.error_code_content_already_playing);
        map.put(PlaybackStateCompat.ERROR_CODE_SKIP_LIMIT_REACHED,
                R.string.error_code_skip_limit_reached);
        map.put(PlaybackStateCompat.ERROR_CODE_ACTION_ABORTED, R.string.error_code_action_aborted);
        map.put(PlaybackStateCompat.ERROR_CODE_END_OF_QUEUE, R.string.error_code_end_of_queue);
        ERROR_CODE_MESSAGES_MAP = Collections.unmodifiableMap(map);
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
        }
        mMode = localViewModel.getSavedMode();

        mRootView = findViewById(R.id.media_activity_root);
        mAppBarView = findViewById(R.id.app_bar);
        mAppBarView.setListener(mAppBarListener);
        mediaSourceViewModel.getPrimaryMediaSource().observe(this,
                this::onMediaSourceChanged);

        mEmptyFragment = new EmptyFragment();
        MediaBrowserViewModel mediaBrowserViewModel = getRootBrowserViewModel();
        mediaBrowserViewModel.getBrowseState().observe(this,
                browseState -> mEmptyFragment.setState(browseState,
                        mediaSourceViewModel.getPrimaryMediaSource().getValue()));
        mediaBrowserViewModel.getBrowsedMediaItems().observe(this, futureData -> {
            if (futureData.isLoading()) {
                if (Log.isLoggable(TAG, Log.INFO)) {
                    Log.i(TAG, "Loading browse tree...");
                }
                mBrowseTreeHasChildren = false;
                return;
            }
            final boolean browseTreeHasChildren =
                    futureData.getData() != null && !futureData.getData().isEmpty();
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "Browse tree loaded, status (has children or not) changed: "
                        + mBrowseTreeHasChildren + " -> " + browseTreeHasChildren);
            }
            mBrowseTreeHasChildren = browseTreeHasChildren;
            handlePlaybackState(playbackViewModel.getPlaybackStateWrapper().getValue(), false);
            updateTabs(futureData.getData());
        });
        mediaBrowserViewModel.supportsSearch().observe(this,
                mAppBarView::setSearchSupported);

        mPlaybackFragment = new PlaybackFragment();
        mPlaybackFragment.setListener(mPlaybackFragmentListener);
        mSearchFragment = BrowseFragment.newSearchInstance();

        Size maxArtSize = MediaAppConfig.getMediaItemsBitmapMaxSize(this);
        mMiniPlaybackControls = findViewById(R.id.minimized_playback_controls);
        mMiniPlaybackControls.setModel(playbackViewModel, this, maxArtSize);
        mMiniPlaybackControls.setOnClickListener(view -> changeMode(Mode.PLAYBACK));

        mFadeDuration = getResources().getInteger(R.integer.new_album_art_fade_in_duration);
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

        playbackViewModel.getPlaybackStateWrapper().observe(this,
                state -> handlePlaybackState(state, true));

        mCarUxRestrictionsUtil = CarUxRestrictionsUtil.getInstance(this);
        mRestrictions = CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP;
        mCarUxRestrictionsUtil.register(mListener);

        mPlaybackContainer.setOnTouchListener(new ClosePlaybackDetector(this));
        mAppSelectorIntent = MediaSource.getSourceSelectorIntent(this, false);
        mAppBarView.setAppLauncherSupported(mAppSelectorIntent != null);
    }

    @Override
    protected void onDestroy() {
        mCarUxRestrictionsUtil.unregister(mListener);
        super.onDestroy();
    }

    private boolean isUxRestricted() {
        return CarUxRestrictionsUtil.isRestricted(mRestrictions, mActiveCarUxRestrictions);
    }

    private void handlePlaybackState(PlaybackViewModel.PlaybackStateWrapper state,
            boolean ignoreSameState) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG,
                    "handlePlaybackState(); state change: " + (mCurrentPlaybackStateWrapper != null
                            ? mCurrentPlaybackStateWrapper.getState() : null) + " -> " + (
                            state != null ? state.getState() : null));

        }

        // TODO(arnaudberry) rethink interactions between customized layouts and dynamic visibility.
        mCanShowMiniPlaybackControls = (state != null) && state.shouldDisplay();
        updateMiniPlaybackControls();

        if (state == null) {
            mCurrentPlaybackStateWrapper = null;
            return;
        }

        String displayedMessage = getDisplayedMessage(state);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Displayed error message: [" + displayedMessage + "]");
        }
        if (ignoreSameState && mCurrentPlaybackStateWrapper != null
                && mCurrentPlaybackStateWrapper.getState() == state.getState()
                && TextUtils.equals(displayedMessage,
                getDisplayedMessage(mCurrentPlaybackStateWrapper))) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Ignore same playback state.");
            }
            return;
        }

        mCurrentPlaybackStateWrapper = state;

        maybeCancelToast();
        maybeCancelDialog();

        Bundle extras = state.getExtras();
        PendingIntent intent = extras == null ? null : extras.getParcelable(
                MediaConstants.ERROR_RESOLUTION_ACTION_INTENT);
        String label = extras == null ? null : extras.getString(
                MediaConstants.ERROR_RESOLUTION_ACTION_LABEL);

        boolean isFatalError = false;
        if (!TextUtils.isEmpty(displayedMessage)) {
            if (mBrowseTreeHasChildren) {
                if (intent != null && !isUxRestricted()) {
                    showDialog(intent, displayedMessage, label, getString(android.R.string.cancel));
                } else {
                    showToast(displayedMessage);
                }
            } else {
                mErrorFragment = ErrorFragment.newInstance(displayedMessage, label, intent);
                setErrorFragment(mErrorFragment);
                isFatalError = true;
            }
        }
        if (isFatalError) {
            changeMode(Mode.FATAL_ERROR);
        } else if (mMode == Mode.FATAL_ERROR) {
            changeMode(Mode.BROWSING);
        }
    }

    private String getDisplayedMessage(@Nullable PlaybackViewModel.PlaybackStateWrapper state) {
        if (state == null) {
            return null;
        }
        if (!TextUtils.isEmpty(state.getErrorMessage())) {
            return state.getErrorMessage().toString();
        }
        // ERROR_CODE_UNKNOWN_ERROR means there is no error in PlaybackState.
        if (state.getErrorCode() != PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR) {
            Integer messageId = ERROR_CODE_MESSAGES_MAP.get(state.getErrorCode());
            return messageId != null ? getString(messageId) : getString(
                    R.string.default_error_message);
        }
        if (state.getState() == PlaybackStateCompat.STATE_ERROR) {
            return getString(R.string.default_error_message);
        }
        return null;
    }

    private void showDialog(PendingIntent intent, String message, String positiveBtnText,
            String negativeButtonText) {
        AlertDialogBuilder dialog = new AlertDialogBuilder(this);
        mDialog = dialog.setMessage(message)
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

    private void maybeCancelDialog() {
        if (mDialog != null) {
            mDialog.cancel();
            mDialog = null;
        }
    }

    private void showToast(String message) {
        mToast = Toast.makeText(this, message, Toast.LENGTH_LONG);
        mToast.show();
    }

    private void maybeCancelToast() {
        if (mToast != null) {
            mToast.cancel();
            mToast = null;
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    /**
     * Sets the media source being browsed.
     *
     * @param mediaSource the new media source we are going to try to browse
     */
    private void onMediaSourceChanged(@Nullable MediaSource mediaSource) {
        if (Log.isLoggable(TAG, Log.INFO)) {
            Log.i(TAG, "MediaSource changed to " + mediaSource);
        }

        mBrowseTreeHasChildren = false;
        mCurrentPlaybackStateWrapper = null;
        maybeCancelToast();
        maybeCancelDialog();
        updateTabs(null);
        mAppBarView.setTitle(null);
        mAppBarView.setMediaAppTitle(mediaSource != null ? mediaSource.getDisplayName() : null);
        if (mediaSource != null) {
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "Browsing: " + mediaSource.getDisplayName());
            }
            mSearchFragment.resetSearchState();
            // Changes the mode regardless of its previous value so that the views can be updated.
            changeModeInternal(Mode.BROWSING);
            updateSourcePreferences(mediaSource.getPackageName());
            // Always go through the trampoline activity to keep all the dispatching logic there.
            startActivity(new Intent(Car.CAR_INTENT_ACTION_MEDIA_TEMPLATE));
        } else {
            updateSourcePreferences(null);
        }
    }

    // TODO(b/136274938): display the preference screen for each media service.
    private void updateSourcePreferences(@Nullable String packageName) {
        mCurrentSourcePreferences = null;
        if (packageName != null) {
            Intent prefsIntent = new Intent(Intent.ACTION_APPLICATION_PREFERENCES);
            prefsIntent.setPackage(packageName);
            ResolveInfo info = getPackageManager().resolveActivity(prefsIntent, 0);
            if (info != null && info.activityInfo != null && info.activityInfo.exported) {
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
        if (browsableTopLevel.size() == 1) {
            // If there is only a single tab, use it as a header instead
            mAppBarView.setMediaAppTitle(browsableTopLevel.get(0).getTitle());
            mAppBarView.setTitle(null);
            mAppBarView.setItems(null);
        } else {
            mAppBarView.setItems(browsableTopLevel);
        }
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
        return mMode == Mode.SEARCHING ? mSearchFragment : mBrowseFragment;
    }

    private void changeMode(Mode mode) {
        if (mMode == mode) {
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "Mode " + mMode + " change is ignored");
            }
            return;
        }
        changeModeInternal(mode);
    }

    private void changeModeInternal(Mode mode) {
        if (Log.isLoggable(TAG, Log.INFO)) {
            Log.i(TAG, "Changing mode from: " + mMode + " to: " + mode);
        }

        Mode oldMode = mMode;
        getInnerViewModel().saveMode(mode);
        mMode = mode;

        mPlaybackFragment.closeOverflowMenu();
        updateMiniPlaybackControls();

        switch (mMode) {
            case FATAL_ERROR:
                ViewUtils.showViewAnimated(mErrorContainer, mFadeDuration);
                ViewUtils.hideViewAnimated(mPlaybackContainer, mFadeDuration);
                ViewUtils.hideViewAnimated(mBrowseContainer, mFadeDuration);
                ViewUtils.hideViewAnimated(mSearchContainer, mFadeDuration);
                mAppBarView.setState(Toolbar.State.HOME);
                break;
            case PLAYBACK:
                mPlaybackContainer.setY(0);
                mPlaybackContainer.setAlpha(0f);
                ViewUtils.hideViewAnimated(mErrorContainer, mFadeDuration);
                ViewUtils.showViewAnimated(mPlaybackContainer, mFadeDuration);
                ViewUtils.hideViewAnimated(mBrowseContainer, mFadeDuration);
                ViewUtils.hideViewAnimated(mSearchContainer, mFadeDuration);
                ViewUtils.hideViewAnimated(mAppBarView, mFadeDuration);
                break;
            case BROWSING:
                if (oldMode == Mode.PLAYBACK) {
                    ViewUtils.hideViewAnimated(mErrorContainer, 0);
                    ViewUtils.showViewAnimated(mBrowseContainer, 0);
                    ViewUtils.hideViewAnimated(mSearchContainer, 0);
                    ViewUtils.showViewAnimated(mAppBarView, 0);
                    mPlaybackContainer.animate()
                            .translationY(mRootView.getHeight())
                            .setDuration(mFadeDuration)
                            .setListener(ViewUtils.hideViewAfterAnimation(mPlaybackContainer))
                            .start();
                } else {
                    ViewUtils.hideViewAnimated(mErrorContainer, mFadeDuration);
                    ViewUtils.hideViewAnimated(mPlaybackContainer, mFadeDuration);
                    ViewUtils.showViewAnimated(mBrowseContainer, mFadeDuration);
                    ViewUtils.hideViewAnimated(mSearchContainer, mFadeDuration);
                    ViewUtils.showViewAnimated(mAppBarView, mFadeDuration);
                }
                updateAppBar();
                break;
            case SEARCHING:
                ViewUtils.hideViewAnimated(mErrorContainer, mFadeDuration);
                ViewUtils.hideViewAnimated(mPlaybackContainer, mFadeDuration);
                ViewUtils.hideViewAnimated(mBrowseContainer, mFadeDuration);
                ViewUtils.showViewAnimated(mSearchContainer, mFadeDuration);
                ViewUtils.showViewAnimated(mAppBarView, mFadeDuration);
                updateAppBar();
                break;
        }
    }

    public int getAppBarHeight() {
        return mAppBarView.getHeight();
    }

    private void updateAppBar() {
        BrowseFragment fragment = getCurrentBrowseFragment();
        boolean isStacked = fragment != null && !fragment.isAtTopStack();
        MediaSource mediaSource = getMediaSourceViewModel().getPrimaryMediaSource().getValue();
        Toolbar.State unstackedState = mMode == Mode.SEARCHING
                ? Toolbar.State.SEARCH
                : Toolbar.State.HOME;
        mAppBarView.setTitle(isStacked ? fragment.getCurrentMediaItem().getTitle() : null);
        mAppBarView.setLogo(mediaSource != null
                ? new BitmapDrawable(getResources(), mediaSource.getRoundPackageIcon())
                : null);
        mAppBarView.setState(isStacked ? Toolbar.State.SUBPAGE : unstackedState);
    }

    private void updateMiniPlaybackControls() {
        // Minimized control bar should be hidden in playback view.
        final boolean shouldShowMiniPlaybackControls =
                mCanShowMiniPlaybackControls && mMode != Mode.PLAYBACK;
        if (shouldShowMiniPlaybackControls) {
            ViewUtils.showViewAnimated(mMiniPlaybackControls, mFadeDuration);
        } else {
            ViewUtils.hideViewAnimated(mMiniPlaybackControls, mFadeDuration);
        }
        getInnerViewModel().setMiniControlsVisible(shouldShowMiniPlaybackControls);
    }

    @Override
    public void onBackStackChanged() {
        updateAppBar();
    }

    @Override
    public void onPlayableItemClicked(MediaItemMetadata item) {
        mPlaybackController.playItem(item);
        boolean switchToPlayback = getResources().getBoolean(
                R.bool.switch_to_playback_view_when_playable_item_is_clicked);
        if (switchToPlayback) {
            changeMode(Mode.PLAYBACK);
        } else if (mMode == Mode.SEARCHING) {
            changeMode(Mode.BROWSING);
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

    public static class ViewModel extends AndroidViewModel {
        private boolean mNeedsInitialization = true;
        private PlaybackViewModel mPlaybackViewModel;
        /** Saves the Mode across config changes. */
        private Mode mSavedMode;

        private MutableLiveData<Boolean> mIsMiniControlsVisible = new MutableLiveData<>();

        public ViewModel(@NonNull Application application) {
            super(application);
        }

        void init(@NonNull PlaybackViewModel playbackViewModel) {
            if (mPlaybackViewModel == playbackViewModel) {
                return;
            }
            mPlaybackViewModel = playbackViewModel;
            mSavedMode = Mode.BROWSING;
            mNeedsInitialization = false;
        }

        boolean needsInitialization() {
            return mNeedsInitialization;
        }

        void setMiniControlsVisible(boolean visible) {
            mIsMiniControlsVisible.setValue(visible);
        }

        LiveData<Boolean> getMiniControlsVisible() {
            return mIsMiniControlsVisible;
        }

        void saveMode(Mode mode) {
            mSavedMode = mode;
        }

        Mode getSavedMode() {
            return mSavedMode;
        }
    }


    private class ClosePlaybackDetector extends GestureDetector.SimpleOnGestureListener
            implements View.OnTouchListener {

        private final ViewConfiguration mViewConfig;
        private final GestureDetectorCompat mDetector;


        ClosePlaybackDetector(Context context) {
            mViewConfig = ViewConfiguration.get(context);
            mDetector = new GestureDetectorCompat(context, this);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            return mDetector.onTouchEvent(event);
        }

        @Override
        public boolean onDown(MotionEvent event) {
            return (mMode == Mode.PLAYBACK);
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
            float dY = e2.getY() - e1.getY();
            if (dY > mViewConfig.getScaledTouchSlop() &&
                    Math.abs(vY) > mViewConfig.getScaledMinimumFlingVelocity()) {
                float dX = e2.getX() - e1.getX();
                float tan = Math.abs(dX) / dY;
                if (tan <= 0.58) { // Accept 30 degrees on each side of the down vector.
                    changeMode(Mode.BROWSING);
                }
            }
            return true;
        }
    }
}
