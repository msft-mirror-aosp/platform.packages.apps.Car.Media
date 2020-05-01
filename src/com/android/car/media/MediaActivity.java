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

import static android.car.media.CarMediaManager.MEDIA_SOURCE_MODE_BROWSE;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Application;
import android.app.PendingIntent;
import android.car.Car;
import android.car.drivingstate.CarUxRestrictions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProviders;

import com.android.car.apps.common.util.ViewUtils;
import com.android.car.media.common.MediaConstants;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.MinimizedPlaybackControlBar;
import com.android.car.media.common.playback.PlaybackViewModel;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.source.MediaSourceViewModel;
import com.android.car.ui.AlertDialogBuilder;
import com.android.car.ui.utils.CarUxRestrictionsUtil;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

/**
 * This activity controls the UI of media. It also updates the connection status for the media app
 * by broadcast.
 */
public class MediaActivity extends FragmentActivity implements BrowseViewController.Callbacks {
    private static final String TAG = "MediaActivity";

    /** Configuration (controlled from resources) */
    private int mFadeDuration;

    /** Models */
    private PlaybackViewModel.PlaybackController mPlaybackController;

    /** Layout views */
    private View mRootView;
    private PlaybackFragment mPlaybackFragment;
    private BrowseViewController mSearchController;
    private BrowseViewController mBrowseController;
    private MinimizedPlaybackControlBar mMiniPlaybackControls;
    private ViewGroup mBrowseContainer;
    private ViewGroup mPlaybackContainer;
    private ViewGroup mErrorContainer;
    private ErrorViewController mErrorController;
    private ViewGroup mSearchContainer;

    private Toast mToast;
    private AlertDialog mDialog;

    /** Current state */
    private Mode mMode;
    private boolean mCanShowMiniPlaybackControls;
    private PlaybackViewModel.PlaybackStateWrapper mCurrentPlaybackStateWrapper;

    private CarUxRestrictionsUtil mCarUxRestrictionsUtil;
    private CarUxRestrictions mActiveCarUxRestrictions;
    @CarUxRestrictions.CarUxRestrictionsInfo
    private int mRestrictions;
    private final CarUxRestrictionsUtil.OnUxRestrictionsChangedListener mListener =
            (carUxRestrictions) -> mActiveCarUxRestrictions = carUxRestrictions;


    private PlaybackFragment.PlaybackFragmentListener mPlaybackFragmentListener =
            () -> changeMode(Mode.BROWSING);

    /**
     * Possible modes of the application UI
     */
    enum Mode {
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
        // TODO(b/151174811): Use appropriate modes, instead of just MEDIA_SOURCE_MODE_BROWSE
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

        mediaSourceViewModel.getPrimaryMediaSource().observe(this,
                this::onMediaSourceChanged);

        mPlaybackFragment = new PlaybackFragment();
        mPlaybackFragment.setListener(mPlaybackFragmentListener);


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

        mBrowseController = BrowseViewController.newInstance(this, mBrowseContainer);
        mSearchController = BrowseViewController.newSearchInstance(this, mSearchContainer);

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

        localViewModel.getMiniControlsVisible().observe(this, visible -> {
            mBrowseController.onPlaybackControlsChanged(visible);
            mSearchController.onPlaybackControlsChanged(visible);
        });
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
        updateMiniPlaybackControls(true);

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
            if (mBrowseController.browseTreeHasChildren()) {
                if (intent != null && !isUxRestricted()) {
                    showDialog(intent, displayedMessage, label, getString(android.R.string.cancel));
                } else {
                    showToast(displayedMessage);
                }
            } else {
                getErrorController().setError(displayedMessage, label, intent);
                isFatalError = true;
            }
        }
        if (isFatalError) {
            changeMode(Mode.FATAL_ERROR);
        } else if (mMode == Mode.FATAL_ERROR) {
            changeMode(Mode.BROWSING);
        }
    }

    private ErrorViewController getErrorController() {
        if (mErrorController == null) {
            mErrorController = new ErrorViewController(this, mErrorContainer);
            MediaSource mediaSource = getMediaSourceViewModel().getPrimaryMediaSource().getValue();
            mErrorController.onMediaSourceChanged(mediaSource);
        }
        return mErrorController;
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
        switch (mMode) {
            case PLAYBACK:
                changeMode(Mode.BROWSING);
                break;
            case SEARCHING:
                mSearchController.onBackPressed();
                break;
            case BROWSING:
                boolean handled = mBrowseController.onBackPressed();
                if (handled) return;
                // Fall through.
            case FATAL_ERROR:
            default:
                super.onBackPressed();
        }
    }

    /**
     * Sets the media source being browsed.
     *
     * @param mediaSource the new media source we are going to try to browse
     */
    private void onMediaSourceChanged(@Nullable MediaSource mediaSource) {
        ComponentName savedMediaSource = getInnerViewModel().getSavedMediaSource();
        if (Log.isLoggable(TAG, Log.INFO)) {
            Log.i(TAG, "MediaSource changed from " + savedMediaSource + " to " + mediaSource);
        }

        savedMediaSource = mediaSource != null ? mediaSource.getBrowseServiceComponentName() : null;
        getInnerViewModel().saveMediaSource(savedMediaSource);

        mBrowseController.onMediaSourceChanged(mediaSource);
        mSearchController.onMediaSourceChanged(mediaSource);
        if (mErrorController != null) {
            mErrorController.onMediaSourceChanged(mediaSource);
        }

        mCurrentPlaybackStateWrapper = null;
        maybeCancelToast();
        maybeCancelDialog();
        if (mediaSource != null) {
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "Browsing: " + mediaSource.getDisplayName());
            }
            Mode mediaSourceMode = getInnerViewModel().getSavedMode();
            // Changes the mode regardless of its previous value so that the views can be updated.
            changeModeInternal(mediaSourceMode, false);

            // Always go through the trampoline activity to keep all the dispatching logic there.
            startActivity(new Intent(Car.CAR_INTENT_ACTION_MEDIA_TEMPLATE));
        }
    }

    @Override
    public void changeMode(Mode mode) {
        if (mMode == mode) {
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "Mode " + mMode + " change is ignored");
            }
            return;
        }
        changeModeInternal(mode, true);
    }

    private void changeModeInternal(Mode mode, boolean hideViewAnimated) {
        if (Log.isLoggable(TAG, Log.INFO)) {
            Log.i(TAG, "Changing mode from: " + mMode + " to: " + mode);
        }
        int fadeOutDuration = hideViewAnimated ? mFadeDuration : 0;

        Mode oldMode = mMode;
        getInnerViewModel().saveMode(mode);
        mMode = mode;

        mPlaybackFragment.closeOverflowMenu();
        updateMiniPlaybackControls(hideViewAnimated);

        switch (mMode) {
            case FATAL_ERROR:
                ViewUtils.showViewAnimated(mErrorContainer, mFadeDuration);
                ViewUtils.hideViewAnimated(mPlaybackContainer, fadeOutDuration);
                ViewUtils.hideViewAnimated(mBrowseContainer, fadeOutDuration);
                ViewUtils.hideViewAnimated(mSearchContainer, fadeOutDuration);
                break;
            case PLAYBACK:
                mPlaybackContainer.setY(0);
                mPlaybackContainer.setAlpha(0f);
                ViewUtils.hideViewAnimated(mErrorContainer, fadeOutDuration);
                ViewUtils.showViewAnimated(mPlaybackContainer, mFadeDuration);
                ViewUtils.hideViewAnimated(mBrowseContainer, fadeOutDuration);
                ViewUtils.hideViewAnimated(mSearchContainer, fadeOutDuration);
                break;
            case BROWSING:
                if (oldMode == Mode.PLAYBACK) {
                    ViewUtils.hideViewAnimated(mErrorContainer, 0);
                    ViewUtils.showViewAnimated(mBrowseContainer, 0);
                    ViewUtils.hideViewAnimated(mSearchContainer, 0);
                    mPlaybackContainer.animate()
                            .translationY(mRootView.getHeight())
                            .setDuration(fadeOutDuration)
                            .setListener(ViewUtils.hideViewAfterAnimation(mPlaybackContainer))
                            .start();
                } else {
                    ViewUtils.hideViewAnimated(mErrorContainer, fadeOutDuration);
                    ViewUtils.hideViewAnimated(mPlaybackContainer, fadeOutDuration);
                    ViewUtils.showViewAnimated(mBrowseContainer, mFadeDuration);
                    ViewUtils.hideViewAnimated(mSearchContainer, fadeOutDuration);
                }
                break;
            case SEARCHING:
                ViewUtils.hideViewAnimated(mErrorContainer, fadeOutDuration);
                ViewUtils.hideViewAnimated(mPlaybackContainer, fadeOutDuration);
                ViewUtils.hideViewAnimated(mBrowseContainer, fadeOutDuration);
                ViewUtils.showViewAnimated(mSearchContainer, mFadeDuration);
                break;
        }
    }

    private void updateMiniPlaybackControls(boolean hideViewAnimated) {
        int fadeOutDuration = hideViewAnimated ? mFadeDuration : 0;
        // Minimized control bar should be hidden in playback view.
        final boolean shouldShowMiniPlaybackControls =
                mCanShowMiniPlaybackControls && mMode != Mode.PLAYBACK;
        if (shouldShowMiniPlaybackControls) {
            ViewUtils.showViewAnimated(mMiniPlaybackControls, mFadeDuration);
        } else {
            ViewUtils.hideViewAnimated(mMiniPlaybackControls, fadeOutDuration);
        }
        getInnerViewModel().setMiniControlsVisible(shouldShowMiniPlaybackControls);
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

    @Override
    public void onRootLoaded() {
        PlaybackViewModel playbackViewModel = getPlaybackViewModel();
        handlePlaybackState(playbackViewModel.getPlaybackStateWrapper().getValue(), false);
    }

    @Override
    public FragmentActivity getActivity() {
        return this;
    }

    private MediaSourceViewModel getMediaSourceViewModel() {
        return MediaSourceViewModel.get(getApplication(), MEDIA_SOURCE_MODE_BROWSE);
    }

    private PlaybackViewModel getPlaybackViewModel() {
        return PlaybackViewModel.get(getApplication(), MEDIA_SOURCE_MODE_BROWSE);
    }

    private ViewModel getInnerViewModel() {
        return ViewModelProviders.of(this).get(ViewModel.class);
    }

    public static class ViewModel extends AndroidViewModel {

        static class MediaServiceState {
            Mode mMode = Mode.BROWSING;
            Stack<MediaItemMetadata> mBrowseStack = new Stack<>();
            Stack<MediaItemMetadata> mSearchStack = new Stack<>();
            String mSearchQuery;
            boolean mQueueVisible = false;
        }

        private boolean mNeedsInitialization = true;
        private PlaybackViewModel mPlaybackViewModel;
        private ComponentName mMediaSource;
        private final Map<ComponentName, MediaServiceState> mStates = new HashMap<>();
        private MutableLiveData<Boolean> mIsMiniControlsVisible = new MutableLiveData<>();

        public ViewModel(@NonNull Application application) {
            super(application);
        }

        void init(@NonNull PlaybackViewModel playbackViewModel) {
            if (mPlaybackViewModel == playbackViewModel) {
                return;
            }
            mPlaybackViewModel = playbackViewModel;
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

        MediaServiceState getSavedState() {
            MediaServiceState state = mStates.get(mMediaSource);
            if (state == null) {
                state = new MediaServiceState();
                mStates.put(mMediaSource, state);
            }
            return state;
        }

        void saveMode(Mode mode) {
            getSavedState().mMode = mode;
        }

        Mode getSavedMode() {
            return getSavedState().mMode;
        }

        @Nullable
        MediaItemMetadata getSelectedTab() {
            Stack<MediaItemMetadata> stack = getSavedState().mBrowseStack;
            return (stack != null && !stack.empty()) ? stack.firstElement() : null;
        }

        void setQueueVisible(boolean visible) {
            getSavedState().mQueueVisible = visible;
        }

        boolean getQueueVisible() {
            return getSavedState().mQueueVisible;
        }

        void saveMediaSource(ComponentName mediaSource) {
            mMediaSource = mediaSource;
        }

        ComponentName getSavedMediaSource() {
            return mMediaSource;
        }

        Stack<MediaItemMetadata> getBrowseStack() {
            return getSavedState().mBrowseStack;
        }

        Stack<MediaItemMetadata> getSearchStack() {
            return getSavedState().mSearchStack;
        }

        String getSearchQuery() {
            return getSavedState().mSearchQuery;
        }

        void setSearchQuery(String searchQuery) {
            getSavedState().mSearchQuery = searchQuery;
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
