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
import static android.car.media.CarMediaManager.MEDIA_SOURCE_MODE_PLAYBACK;

import static androidx.car.app.mediaextensions.analytics.event.AnalyticsEvent.VIEW_ACTION_HIDE;
import static androidx.car.app.mediaextensions.analytics.event.AnalyticsEvent.VIEW_ACTION_SHOW;
import static androidx.car.app.mediaextensions.analytics.event.AnalyticsEvent.VIEW_COMPONENT_BROWSE_LIST;
import static androidx.car.app.mediaextensions.analytics.event.AnalyticsEvent.VIEW_COMPONENT_ERROR_MESSAGE;
import static androidx.car.app.mediaextensions.analytics.event.AnalyticsEvent.VIEW_COMPONENT_MEDIA_HOST;
import static androidx.car.app.mediaextensions.analytics.event.AnalyticsEvent.VIEW_COMPONENT_PLAYBACK;

import static com.android.car.apps.common.util.LiveDataFunctions.dataOf;
import static com.android.car.apps.common.util.VectorMath.EPSILON;
import static com.android.car.media.MediaDispatcherActivity.KEY_MEDIA_ID;

import android.annotation.SuppressLint;
import android.app.ActivityManager.TaskDescription;
import android.app.AlertDialog;
import android.app.Application;
import android.app.PendingIntent;
import android.car.Car;
import android.car.content.pm.CarPackageManager;
import android.car.drivingstate.CarUxRestrictions;
import android.car.media.CarMediaIntents;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.view.GestureDetectorCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.apps.common.BitmapUtils;
import com.android.car.apps.common.util.CarPackageManagerUtils;
import com.android.car.apps.common.util.FutureData;
import com.android.car.apps.common.util.IntentUtils;
import com.android.car.apps.common.util.VectorMath;
import com.android.car.apps.common.util.ViewUtils;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.MinimizedPlaybackControlBar;
import com.android.car.media.common.PlaybackErrorsHelper;
import com.android.car.media.common.browse.MediaItemsRepository;
import com.android.car.media.common.playback.PlaybackViewModel;
import com.android.car.media.common.source.MediaModels;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.source.MediaSourceViewModel;
import com.android.car.media.common.ui.PlaybackCardViewModel;
import com.android.car.ui.AlertDialogBuilder;
import com.android.car.ui.utils.CarUxRestrictionsUtil;

import java.util.Objects;

/**
 * This activity controls the UI of media. It also updates the connection status for the media app
 * by broadcast.
 */
@OptIn(markerClass = androidx.car.app.annotations2.ExperimentalCarApi.class)
public class MediaActivity extends FragmentActivity implements MediaActivityController.Callbacks {
    private static final String TAG = "MediaActivity";

    /** Configuration (controlled from resources) */
    private int mFadeDuration;

    /** Models */
    private PlaybackViewModel.PlaybackController mBrowsePlaybackController;

    /** Layout views */
    private MediaActivityController mMediaActivityController;
    private MinimizedPlaybackControlBar mMiniPlaybackControls;
    private ViewGroup mBrowseContainer;
    private ViewGroup mPlaybackContainer;
    private ViewGroup mErrorContainer;
    private ErrorScreenController mErrorController;

    private Toast mToast;
    private AlertDialog mDialog;

    /** Current state */
    private Mode mMode;
    private boolean mCanShowMiniPlaybackControls;

    private Car mCar;
    private CarPackageManager mCarPackageManager;

    private float mCloseVectorX;
    private float mCloseVectorY;
    private float mCloseVectorNorm;

    /**
     * Possible modes of the application UI
     * Todo: refactor into non exclusive flags to allow concurrent modes (eg: play details & browse)
     * (b/179292793).
     */
    enum Mode {
        /** The user is browsing or searching a media source */
        BROWSING,
        /** The user is interacting with the full screen playback UI */
        PLAYBACK,
        /** There's no browse tree and playback doesn't work. */
        FATAL_ERROR
    }

    protected void initializeLocalViewModel(ViewModel localViewModel, MediaSource browsedSource) {
        CarMediaApp app = (CarMediaApp) getApplication();

        MediaModels[] models = {null, null};

        // Create the models for the browse mode. They are based on the given browsedSource to
        // which this MediaActivity instance is permanently tied. New sources are opened in a
        // different MediaActivity instance.
        models[MEDIA_SOURCE_MODE_BROWSE] = new MediaModels(app, browsedSource);

        // Create the models for the playback mode.
        if (getResources().getBoolean(R.bool.show_playback_media_source)) {
            models[MEDIA_SOURCE_MODE_PLAYBACK] = app.getMediaModelsForPlaybackMode();
        } else {
            // No media continuity, use the browse mode models
            models[MEDIA_SOURCE_MODE_PLAYBACK] = models[MEDIA_SOURCE_MODE_BROWSE];
        }

        localViewModel.init(models);
    }

    /**
     * Creates an intent to open a MediaActivity for the given source.
     * @param mediaId optional media id of the node to browse to.
     */
    public static Intent createMediaActivityIntent(Context context, MediaSource source,
            @Nullable String mediaId) {
        Intent newIntent = new Intent(context, MediaActivity.class);
        ComponentName mbs = source.getBrowseServiceComponentName();
        newIntent.setData(Uri.parse("custom:/" + mbs.flattenToString()));

        if (mediaId != null) {
            newIntent.putExtra(KEY_MEDIA_ID, mediaId);
        }

        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return newIntent;
    }

    @Nullable
    private MediaSource getIntentSource(Intent intent) {
        Uri uri = intent.getData();
        String path = (uri != null) ? uri.getPath() : null;
        String name = !TextUtils.isEmpty(path) && path.startsWith("/") ? path.substring(1) : path;
        ComponentName sourceComp = (name == null) ? null : ComponentName.unflattenFromString(name);
        return (sourceComp == null) ? null : MediaSource.create(this, sourceComp);
    }

    @Nullable
    private String getIntentMediaId(Intent intent) {
        return (intent.hasExtra(KEY_MEDIA_ID)) ? intent.getStringExtra(KEY_MEDIA_ID) : null;
    }

    @Override
    public void setTaskDescription(TaskDescription taskDescription) {
        Intent intent = getIntent();
        if (intent != null) {
            MediaSource source = getIntentSource(intent);
            if (source != null) {
                String label = source.getDisplayName(this).toString();
                Drawable dd = source.getIcon();
                int size = getResources().getInteger(R.integer.task_description_icon_pixels);
                Bitmap icon = BitmapUtils.fromDrawable(dd, new Size(size, size));
                taskDescription = new TaskDescription(label, icon);
            }
        }
        super.setTaskDescription(taskDescription);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.media_activity);

        Intent intent = getIntent();
        MediaSource source = null;
        if (intent != null) {
            source = getIntentSource(intent);
            Log.i(TAG, "onCreate source: " + source);
        }

        if (source == null) {
            Log.e(TAG, "MediaActivity requires a valid media source!");
            finish();
            return;
        }

        Resources res = getResources();
        mCloseVectorX = res.getFloat(R.dimen.media_activity_close_vector_x);
        mCloseVectorY = res.getFloat(R.dimen.media_activity_close_vector_y);
        mCloseVectorNorm = VectorMath.norm2(mCloseVectorX, mCloseVectorY);

        // We can't rely on savedInstanceState to determine whether the model has been initialized
        // as on a config change savedInstanceState != null and the model is initialized, but if
        // the app was killed by the system then savedInstanceState != null and the model is NOT
        // initialized...
        ViewModel localViewModel = getInnerViewModel();
        if (localViewModel.needsInitialization()) {
            initializeLocalViewModel(localViewModel, source);
        }

        PlaybackViewModel playbackViewModelBrowse = getPlaybackViewModel(MEDIA_SOURCE_MODE_BROWSE);
        PlaybackViewModel playbackViewModelPlayback = getPlaybackViewModel(
                MEDIA_SOURCE_MODE_PLAYBACK);

        mMode = localViewModel.getSavedMode();

        localViewModel.getBrowsedMediaSource().observe(this, this::onMediaSourceChanged);

        Size maxArtSize = MediaAppConfig.getMediaItemsBitmapMaxSize(this);
        mMiniPlaybackControls = findViewById(R.id.minimized_playback_controls);
        mMiniPlaybackControls.setModel(playbackViewModelPlayback, this,
                localViewModel.getMediaItemsRepository(MEDIA_SOURCE_MODE_PLAYBACK), maxArtSize);
        mMiniPlaybackControls.setOnClickListener(view -> changeMode(Mode.PLAYBACK));

        mFadeDuration = res.getInteger(R.integer.new_album_art_fade_in_duration);
        mBrowseContainer = findViewById(R.id.fragment_container);
        mErrorContainer = findViewById(R.id.error_container);
        mPlaybackContainer = findViewById(R.id.playback_container);

        playbackViewModelBrowse.getPlaybackController().observe(this,
                playbackController -> {
                    if (playbackController != null) playbackController.prepare();
                    mBrowsePlaybackController = playbackController;
                });

        playbackViewModelBrowse.getPlaybackStateWrapper().observe(this,
                state -> handlePlaybackStateFromBrowseSource(state, true,
                        playbackViewModelBrowse.getMediaSource().getValue()));
        playbackViewModelPlayback.getPlaybackStateWrapper().observe(this,
                state -> handlePlaybackStateFromPlaybackSource(state,
                        playbackViewModelPlayback.getMediaSource().getValue()));

        mCar = Car.createCar(this);
        mCarPackageManager = (CarPackageManager) mCar.getCarManager(Car.PACKAGE_SERVICE);

        mMediaActivityController = new MediaActivityController(this, getInnerViewModel(),
                mCarPackageManager, mBrowseContainer, mPlaybackContainer);

        mPlaybackContainer.setOnTouchListener(new ClosePlaybackDetector(this));

        mMediaActivityController.navigateTo(getIntentMediaId(intent));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (intent == null) {
            Log.w(TAG, "onNewIntent called with a null intent ?!");
            return;
        }

        MediaSource current = getInnerViewModel().getMediaSourceValue();
        MediaSource source = getIntentSource(intent);
        if (!Objects.equals(current, source)) {
            Log.e(TAG, "Received incorrect source: " + source + " expected: " + current);
            return;
        }

        // Try to reconnect in case the source crashed or was killed.
        getInnerViewModel().getMediaSourceViewModel(MEDIA_SOURCE_MODE_BROWSE).maybeReconnect();

        String mediaId = getIntentMediaId(intent);
        Log.i(TAG, "onNewIntent source: " + source + " mediaId: " + mediaId);
        mMediaActivityController.navigateTo(mediaId);
    }

    @Override
    protected void onResume() {
        super.onResume();

        getMediaItemsRepository().getAnalyticsManager().sendViewChangedEvent(
                VIEW_COMPONENT_MEDIA_HOST, VIEW_ACTION_SHOW);

        // When the Now playing view shows a different media source (due to media continuity),
        // go back to the browsing view so that the displayed source matches what the user
        // launched.
        if (mMode == Mode.PLAYBACK) {
            PlaybackViewModel model = getPlaybackViewModel(MEDIA_SOURCE_MODE_PLAYBACK);
            MediaSource src = model.getMediaSource().getValue();
            ComponentName playComp = (src != null) ? src.getBrowseServiceComponentName() : null;

            ViewModel localViewModel = getInnerViewModel();
            MediaSource browseSrc = localViewModel.getMediaSourceValue();
            ComponentName browseComp = (browseSrc == null) ? null :
                    browseSrc.getBrowseServiceComponentName();
            if (!Objects.equals(playComp, browseComp)) {
                changeMode(Mode.BROWSING);
            }
        }

        if (mMode == Mode.FATAL_ERROR && mErrorController != null) {
            mErrorController.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        getMediaItemsRepository().getAnalyticsManager().sendViewChangedEvent(
                VIEW_COMPONENT_MEDIA_HOST, VIEW_ACTION_HIDE);
        getMediaItemsRepository().getAnalyticsManager().sendQueue();
    }

    @Override
    protected void onDestroy() {
        mCar.disconnect();
        mMediaActivityController.onDestroy();
        super.onDestroy();
    }

    private boolean isUxRestricted() {
        return CarUxRestrictionsUtil.isRestricted(CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP,
                CarUxRestrictionsUtil.getInstance(this).getCurrentRestrictions());
    }

    private void handlePlaybackStateFromBrowseSource(
            PlaybackViewModel.PlaybackStateWrapper state, boolean ignoreSameState,
            MediaSource mediaSource) {
        mErrorsFromBrowseHelper.handlePlaybackState(TAG, state, ignoreSameState, mediaSource);
    }

    private final PlaybackErrorsHelper mErrorsFromBrowseHelper = new PlaybackErrorsHelper(this) {
        @Override
        public void handleNewPlaybackState(String displayedMessage, PendingIntent intent,
                boolean canAutoLaunch, String label, MediaSource mediaSource) {

            boolean isFatalError = false;
            if (!TextUtils.isEmpty(displayedMessage)) {
                // If browse content -> not fatal
                if (mMediaActivityController.browseTreeHasChildrenList()) {
                    showToastOrDialog(displayedMessage, intent, label, mediaSource);
                } else {
                    boolean isDistractionOptimized =
                            intent != null && CarPackageManagerUtils.isDistractionOptimized(
                                    mCarPackageManager, intent);
                    getErrorController().setError(displayedMessage, label, intent, canAutoLaunch,
                            isDistractionOptimized);
                    isFatalError = true;
                }
            }

            // Only the browse media source can affect the browse area of the UI.
            if (isFatalError) {
                changeMode(MediaActivity.Mode.FATAL_ERROR);
            } else if (mMode == MediaActivity.Mode.FATAL_ERROR) {
                changeMode(MediaActivity.Mode.BROWSING);
            }
        }
    };

    private void handlePlaybackStateFromPlaybackSource(
            PlaybackViewModel.PlaybackStateWrapper state, MediaSource mediaSource) {
        mErrorsFromPlaybackHelper.handlePlaybackState(TAG, state, true, mediaSource);
    }

    private final PlaybackErrorsHelper mErrorsFromPlaybackHelper = new PlaybackErrorsHelper(this) {

        @Override
        public void handlePlaybackState(
                @NonNull String tag, PlaybackViewModel.PlaybackStateWrapper state,
                boolean ignoreSameState, MediaSource mediaSource) {
            // TODO rethink interactions between customized layouts and dynamic visibility.
            // Only the playback media source can change the minimized playback controls.
            mCanShowMiniPlaybackControls = (state != null) && state.shouldDisplay();
            updateMiniPlaybackControls(true);

            super.handlePlaybackState(tag, state, ignoreSameState, mediaSource);
        }

        @Override
        public void handleNewPlaybackState(
                String displayedMessage, PendingIntent intent, boolean canAutoLaunch, String label,
                MediaSource playbackSource) {

            boolean areSourcesDifferent = !Objects.equals(playbackSource,
                    getPlaybackViewModel(MEDIA_SOURCE_MODE_BROWSE).getMediaSource().getValue());

            // When the playback and browse media sources are the same, this playback state will
            // will be handled by mErrorsFromBrowseHelper, so only process it when the sources
            // are different. Also the error is never fatal because that is reserved for the browse
            // source.
            if (areSourcesDifferent && !TextUtils.isEmpty(displayedMessage)) {
                showToastOrDialog(displayedMessage, intent, label, playbackSource);
            }
        }
    };

    private void showToastOrDialog(
            String displayedMessage, PendingIntent intent, String label, MediaSource mediaSource) {
        Drawable icon = mediaSource != null ? mediaSource.getIcon() : null;
        if (intent != null && !isUxRestricted()) {
            maybeCancelDialog();
            showDialog(intent, displayedMessage, label,
                    getString(android.R.string.cancel), icon, mediaSource);
        } else {
            maybeCancelToast();
            showToast(displayedMessage, icon);
        }
    }

    private ErrorScreenController getErrorController() {
        if (mErrorController == null) {
            mErrorController = new ErrorScreenController(this, mCarPackageManager, mErrorContainer);
            MediaSource mediaSource = getInnerViewModel().getMediaSourceValue();
            mErrorController.onMediaSourceChanged(mediaSource);
        }
        return mErrorController;
    }

    private void showDialog(
            PendingIntent intent,
            String message,
            String positiveBtnText,
            String negativeButtonText,
            @Nullable Drawable icon,
            MediaSource mediaSource) {
        boolean showTitleIcon = getResources().getBoolean(R.bool.show_playback_source_id);
        String title = mediaSource != null ? mediaSource.getDisplayName(this).toString() : "";

        AlertDialogBuilder dialog = new AlertDialogBuilder(this);
        mDialog = dialog.setMessage(message)
                .setTitle(showTitleIcon ? title : null)
                .setIcon(showTitleIcon ? icon : null)
                .setNegativeButton(negativeButtonText, null)
                .setPositiveButton(positiveBtnText,
                        (dialogInterface, i) -> IntentUtils.sendIntent(intent))
                .show();
    }

    private void maybeCancelDialog() {
        if (mDialog != null) {
            mDialog.cancel();
            mDialog = null;
        }
    }

    private void showToast(String message, @Nullable Drawable icon) {
        mToast = Toast.makeText(this, message, Toast.LENGTH_LONG);
        int offset = getResources().getDimensionPixelOffset(R.dimen.toast_error_offset_y);
        mToast.setGravity(Gravity.BOTTOM, 0, offset);

        boolean showIcon = getResources().getBoolean(R.bool.show_playback_source_id);

        if (icon != null && showIcon) {
            View view = getLayoutInflater().inflate(R.layout.toast_error, null);
            ((ImageView) view.findViewById(R.id.toast_error_icon)).setImageDrawable(icon);
            ((TextView) view.findViewById(R.id.toast_error_message)).setText(message);
            mToast.setView(view);
        }

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
            case BROWSING:
                boolean handled = mMediaActivityController.onBackPressed();
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
     * @param futureSource contains the new media source we are going to try to browse, as well as
     *                     the old one (either could be null).
     */
    private void onMediaSourceChanged(FutureData<MediaSource> futureSource) {

        MediaSource newMediaSource = FutureData.getData(futureSource);
        MediaSource oldMediaSource = FutureData.getPastData(futureSource);

        if (mErrorController != null) {
            mErrorController.onMediaSourceChanged(newMediaSource);
        }

        maybeCancelToast();
        maybeCancelDialog();
        if (newMediaSource != null) {
            //Tell app that host in visible, this is the first place where there is valid manager
            getMediaItemsRepository().getAnalyticsManager().sendViewChangedEvent(
                    VIEW_COMPONENT_MEDIA_HOST, VIEW_ACTION_SHOW);

            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "Browsing: " + newMediaSource.getDisplayName(this));
            }

            if (Objects.equals(oldMediaSource, newMediaSource)) {
                // The UI is being restored (eg: after a config change) => restore the mode.
                Mode mediaSourceMode = getInnerViewModel().getSavedMode();
                changeModeInternal(mediaSourceMode, false);
            } else {
                // Change the mode regardless of its previous value to update the views.
                // The saved mode is ignored as the media apps don't always recreate a playback
                // state that can be displayed (and some send a displayable state after sending a
                // non displayable one...).
                changeModeInternal(Mode.BROWSING, false);
            }
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

    @Override
    public void changeSource(MediaSource source, MediaItemMetadata mediaItem) {
        Intent newIntent = new Intent(CarMediaIntents.ACTION_MEDIA_TEMPLATE);
        ComponentName mbs = source.getBrowseServiceComponentName();
        newIntent.putExtra(CarMediaIntents.EXTRA_MEDIA_COMPONENT, mbs.flattenToString());
        if (mediaItem != null && !TextUtils.isEmpty(mediaItem.getId())) {
            newIntent.putExtra(KEY_MEDIA_ID, mediaItem.getId());
        }
        startActivity(newIntent);
    }

    @Override
    public boolean getQueueVisible() {
        return getInnerViewModel().getQueueVisible();
    }

    @Override
    public void setQueueVisible(boolean visible) {
        getInnerViewModel().setQueueVisible(visible);
    }

    private void changeModeInternal(Mode mode, boolean hideViewAnimated) {
        if (Log.isLoggable(TAG, Log.INFO)) {
            Log.i(TAG, "Changing mode from: " + mMode + " to: " + mode);
        }
        int fadeOutDuration = hideViewAnimated ? mFadeDuration : 0;

        Mode oldMode = mMode;
        getInnerViewModel().saveMode(mode);
        mMode = mode;

        mMediaActivityController.getNowPlayingController().closeOverflowMenu();
        updateMiniPlaybackControls(hideViewAnimated);

        //Send view exit analytics event, this is needed to calculate time on each screen.
        switch (oldMode) {
            case BROWSING:
                getMediaItemsRepository().getAnalyticsManager().sendViewChangedEvent(
                        VIEW_COMPONENT_BROWSE_LIST, VIEW_ACTION_HIDE);
                break;
            case PLAYBACK:
                mMediaActivityController.onNpvActualVisibilityChanged(false);
                getMediaItemsRepository().getAnalyticsManager().sendViewChangedEvent(
                        VIEW_COMPONENT_PLAYBACK, VIEW_ACTION_HIDE);
                break;
            case FATAL_ERROR:
                getMediaItemsRepository().getAnalyticsManager().sendViewChangedEvent(
                        VIEW_COMPONENT_ERROR_MESSAGE, VIEW_ACTION_HIDE);
                break;
        }

        switch (mMode) {
            case FATAL_ERROR:
                ViewUtils.showViewAnimated(mErrorContainer, mFadeDuration);
                ViewUtils.hideViewAnimated(mPlaybackContainer, fadeOutDuration);
                ViewUtils.hideViewAnimated(mBrowseContainer, fadeOutDuration);
                getMediaItemsRepository().getAnalyticsManager().sendViewChangedEvent(
                        VIEW_COMPONENT_ERROR_MESSAGE, VIEW_ACTION_SHOW);
                break;
            case PLAYBACK:
                mPlaybackContainer.setX(0);
                mPlaybackContainer.setY(0);
                mPlaybackContainer.setAlpha(0f);
                ViewUtils.hideViewAnimated(mErrorContainer, fadeOutDuration);
                // Reporting the view as visible at the end of the animation gives a chance for the
                // new queue to be loaded and avoids reporting the old queue as visible.
                ViewUtils.showViewAnimated(mPlaybackContainer, mFadeDuration,
                        view -> mMediaActivityController.onNpvActualVisibilityChanged(true));
                ViewUtils.hideViewAnimated(mBrowseContainer, fadeOutDuration);
                getMediaItemsRepository().getAnalyticsManager().sendViewChangedEvent(
                        VIEW_COMPONENT_PLAYBACK, VIEW_ACTION_SHOW);
                break;
            case BROWSING:
                if (oldMode == Mode.PLAYBACK) {
                    ViewUtils.hideViewAnimated(mErrorContainer, 0);
                    ViewUtils.showViewAnimated(mBrowseContainer, 0);
                    animateOutPlaybackContainer(fadeOutDuration);
                } else {
                    ViewUtils.hideViewAnimated(mErrorContainer, fadeOutDuration);
                    ViewUtils.hideViewAnimated(mPlaybackContainer, fadeOutDuration);
                    ViewUtils.showViewAnimated(mBrowseContainer, mFadeDuration);
                }
                getMediaItemsRepository().getAnalyticsManager().sendViewChangedEvent(
                        VIEW_COMPONENT_BROWSE_LIST, VIEW_ACTION_SHOW);
                break;
        }
    }

    private void animateOutPlaybackContainer(int fadeOutDuration) {
        if (mCloseVectorNorm <= EPSILON) {
            ViewUtils.hideViewAnimated(mPlaybackContainer, fadeOutDuration);
            return;
        }

        // Assumption: mPlaybackContainer shares 1 edge with the side of the screen the
        // slide animation brings it towards to. Since only vertical and horizontal translations
        // are supported mPlaybackContainer only needs to move by its width or its height to be
        // hidden.

        // Use width and height with and extra pixel for safety.
        float w = mPlaybackContainer.getWidth() + 1;
        float h = mPlaybackContainer.getHeight() + 1;

        float tX = 0.0f;
        float tY = 0.0f;
        if (Math.abs(mCloseVectorY) <= EPSILON) {
            // Only moving horizontally
            tX = mCloseVectorX * w / mCloseVectorNorm;
        } else if (Math.abs(mCloseVectorX) <= EPSILON) {
            // Only moving vertically
            tY = mCloseVectorY * h / mCloseVectorNorm;
        } else {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "The vector to close the playback container must be vertical or"
                        + " horizontal");
            }
            ViewUtils.hideViewAnimated(mPlaybackContainer, fadeOutDuration);
            return;
        }

        mPlaybackContainer.animate()
                .translationX(tX)
                .translationY(tY)
                .setDuration(fadeOutDuration)
                .setListener(ViewUtils.hideViewAfterAnimation(mPlaybackContainer))
                .start();
    }

    private void updateMiniPlaybackControls(boolean hideViewAnimated) {
        int fadeOutDuration = hideViewAnimated ? mFadeDuration : 0;
        // Minimized control bar should be hidden in playback view.
        final boolean shouldShowMiniPlaybackControls =
                getResources().getBoolean(R.bool.show_mini_playback_controls)
                        && mCanShowMiniPlaybackControls
                        && mMode != Mode.PLAYBACK;
        if (shouldShowMiniPlaybackControls) {
            Boolean visible = getInnerViewModel().getMiniControlsVisible().getValue();
            if (visible != Boolean.TRUE) {
                mMiniPlaybackControls.onActualVisibilityChanged(true);
                ViewUtils.showViewAnimated(mMiniPlaybackControls, mFadeDuration);
            }
        } else {
            mMiniPlaybackControls.onActualVisibilityChanged(false);
            ViewUtils.hideViewAnimated(mMiniPlaybackControls, fadeOutDuration);
        }
        getInnerViewModel().setMiniControlsVisible(shouldShowMiniPlaybackControls);
    }

    @Override
    public void onPlayableItemClicked(@NonNull MediaItemMetadata item) {
        mBrowsePlaybackController.playItem(item);
        maybeOpenPlayback();
    }

    @Override
    public void onBrowseEmptyListPlayItemClicked() {
        mBrowsePlaybackController.play();
        maybeOpenPlayback();
    }

    @Override
    public void openPlaybackView() {
        maybeOpenPlayback();
    }

    @Override
    public boolean isBrowseViewVisible() {
        return mMode == Mode.BROWSING;
    }

    private void maybeOpenPlayback() {
        boolean switchToPlayback = getResources().getBoolean(
                R.bool.switch_to_playback_view_when_playable_item_is_clicked);
        if (switchToPlayback) {
            changeMode(Mode.PLAYBACK);
        }
        setIntent(null);
    }

    @Override
    public void onRootLoaded() {
        PlaybackViewModel playbackViewModel = getPlaybackViewModel(MEDIA_SOURCE_MODE_BROWSE);
        handlePlaybackStateFromBrowseSource(playbackViewModel.getPlaybackStateWrapper().getValue(),
                false, playbackViewModel.getMediaSource().getValue());
    }

    @Override
    public FragmentActivity getActivity() {
        return this;
    }

    private MediaItemsRepository getMediaItemsRepository() {
        return getInnerViewModel().getMediaItemsRepository(MEDIA_SOURCE_MODE_BROWSE);
    }

    private PlaybackViewModel getPlaybackViewModel(int mode) {
        return getInnerViewModel().getPlaybackViewModel(mode);
    }

    private ViewModel getInnerViewModel() {
        return new ViewModelProvider(this).get(MediaActivity.ViewModel.class);
    }

    /** State tracking ViewModel for the MediaActivity */
    public static class ViewModel extends PlaybackCardViewModel {

        private Mode mMode = Mode.BROWSING;
        private BrowseStack mBrowseStack = new BrowseStack();
        private String mSearchQuery;
        private boolean mHasPlayableItem = false;
        private MediaModels mBrowseModels;
        private final MutableLiveData<FutureData<MediaSource>> mBrowsedMediaSource =
                dataOf(FutureData.newLoadingData());
        private final MutableLiveData<Boolean> mIsMiniControlsVisible = new MutableLiveData<>();

        public ViewModel(@NonNull Application application) {
            super(application);
        }

        void init(MediaModels[] models) {
            mBrowseModels = models[MEDIA_SOURCE_MODE_BROWSE];
            super.init(models[MEDIA_SOURCE_MODE_PLAYBACK]);
        }

        @Override
        protected void onCleared() {
            if (!needsInitialization()) {
                getMediaSourceViewModel(MEDIA_SOURCE_MODE_BROWSE).onCleared();
            }
            super.onCleared();
        }

        MediaItemsRepository getMediaItemsRepository(int mode) {
            if (mode == MEDIA_SOURCE_MODE_BROWSE) {
                return mBrowseModels.getMediaItemsRepository();
            } else {
                return super.getMediaItemsRepository();
            }
        }

        MediaSourceViewModel getMediaSourceViewModel(int mode) {
            if (mode == MEDIA_SOURCE_MODE_BROWSE) {
                return mBrowseModels.getMediaSourceViewModel();
            } else {
                return super.getMediaSourceViewModel();
            }
        }

        PlaybackViewModel getPlaybackViewModel(int mode) {
            if (mode == MEDIA_SOURCE_MODE_BROWSE) {
                return mBrowseModels.getPlaybackViewModel();
            } else {
                return super.getPlaybackViewModel();
            }
        }

        void setMiniControlsVisible(boolean visible) {
            mIsMiniControlsVisible.setValue(visible);
        }

        LiveData<Boolean> getMiniControlsVisible() {
            return mIsMiniControlsVisible;
        }

        @Nullable
        MediaSource getMediaSourceValue() {
            return getMediaSourceViewModel(MEDIA_SOURCE_MODE_BROWSE).getPrimaryMediaSource()
                    .getValue();
        }

        void saveMode(Mode mode) {
            mMode = mode;
        }

        Mode getSavedMode() {
            return mMode;
        }

        void saveBrowsedMediaSource(MediaSource mediaSource) {
            Resources res = getApplication().getResources();
            if (MediaDispatcherActivity.isCustomMediaSource(res, mediaSource)) {
                Log.i(TAG, "Ignoring custom media source: " + mediaSource);
                return;
            }
            MediaSource oldSource = getMediaSourceValue();
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "MediaSource changed from " + oldSource + " to " + mediaSource);
            }
            mBrowsedMediaSource.setValue(FutureData.newLoadedData(oldSource, mediaSource));
        }

        LiveData<FutureData<MediaSource>> getBrowsedMediaSource() {
            return mBrowsedMediaSource;
        }

        @NonNull BrowseStack getBrowseStack() {
            return mBrowseStack;
        }

        String getSearchQuery() {
            return mSearchQuery;
        }

        void setSearchQuery(String searchQuery) {
            mSearchQuery = searchQuery;
        }

        void setHasPlayableItem(boolean hasPlayableItem) {
            mHasPlayableItem = hasPlayableItem;
        }

        boolean hasPlayableItem() {
            return mHasPlayableItem;
        }
    }

    private class ClosePlaybackDetector extends GestureDetector.SimpleOnGestureListener
            implements View.OnTouchListener {

        private static final float COS_30 = 0.866f;

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
            return (mMode == Mode.PLAYBACK) && (mCloseVectorNorm > EPSILON);
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
            float moveX = e2.getX() - e1.getX();
            float moveY = e2.getY() - e1.getY();
            float moveVectorNorm = VectorMath.norm2(moveX, moveY);
            if (moveVectorNorm > mViewConfig.getScaledTouchSlop() &&
                    VectorMath.norm2(vX, vY) > mViewConfig.getScaledMinimumFlingVelocity()) {
                float dot = VectorMath.dotProduct(mCloseVectorX, mCloseVectorY, moveX, moveY);
                float cos = dot / (mCloseVectorNorm * moveVectorNorm);
                if (cos >= COS_30) { // Accept 30 degrees on each side of the close vector.
                    changeMode(Mode.BROWSING);
                }
            }
            return true;
        }
    }
}
