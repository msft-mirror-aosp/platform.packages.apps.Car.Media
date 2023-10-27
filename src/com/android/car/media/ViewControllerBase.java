/*
 * Copyright 2019 The Android Open Source Project
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

import android.app.PendingIntent;
import android.car.content.pm.CarPackageManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.audiofx.AudioEffect;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.CallSuper;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.android.car.apps.common.util.CarPackageManagerUtils;
import com.android.car.media.common.browse.MediaBrowserViewModelImpl;
import com.android.car.media.common.browse.MediaItemsRepository;
import com.android.car.media.common.source.MediaBrowserConnector;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.source.MediaSourceViewModel;
import com.android.car.media.extensions.analytics.event.AnalyticsEvent;
import com.android.car.media.extensions.analytics.event.ViewChangeEvent;
import com.android.car.media.widgets.AppBarController;
import com.android.car.ui.baselayout.Insets;
import com.android.car.ui.baselayout.InsetsChangedListener;
import com.android.car.ui.core.CarUi;
import com.android.car.ui.toolbar.ToolbarController;

/**
 * Functionality common to content view controllers. It mainly handles the AppBar view,
 * which is common to all of them.
 */
abstract class ViewControllerBase implements InsetsChangedListener {
    private static final String TAG = "ViewControllerBase";

    private final boolean mShouldShowSoundSettings;
    private final CarPackageManager mCarPackageManager;

    final FragmentActivity mActivity;
    final int mFadeDuration;
    final View mContent;
    final AppBarController mAppBarController;
    final MediaSourceViewModel mMediaSourceVM;
    final MediaItemsRepository mMediaItemsRepository;
    private PendingIntent mCurrentSourceBrowserSettings;
    private Intent mCurrentSourcePreferences;

    ViewControllerBase(FragmentActivity activity, MediaItemsRepository mediaItemsRepo,
            CarPackageManager carPackageManager, ViewGroup container, @LayoutRes int resource) {
        mActivity = activity;
        Resources res = mActivity.getResources();
        mFadeDuration = res.getInteger(R.integer.new_album_art_fade_in_duration);
        mShouldShowSoundSettings = res.getBoolean(R.bool.show_sound_settings);

        LayoutInflater inflater = LayoutInflater.from(container.getContext());
        mContent = inflater.inflate(resource, container, false);
        container.addView(mContent);

        GuidelinesUpdater updater = new GuidelinesUpdater(mContent);
        updater.addListener(this);
        ToolbarController toolbar = CarUi.installBaseLayoutAround(mContent, updater, true);

        mAppBarController = new AppBarController(activity, toolbar, R.xml.menuitems_browse,
                res.getBoolean(R.bool.use_media_source_logo_for_app_selector));
        mAppBarController.checkBrowseMenus();
        mAppBarController.setSearchSupported(false);
        mAppBarController.setHasEqualizer(false);

        mCarPackageManager = carPackageManager;

        mMediaSourceVM = MediaSourceViewModel.get(activity.getApplication(),
                MEDIA_SOURCE_MODE_BROWSE);

        mMediaItemsRepository = mediaItemsRepo;

        mMediaItemsRepository.getBrowsingState().observe(activity,
                this::onMediaBrowsingStateChanged);

    }

    @Override
    public void onCarUiInsetsChanged(@NonNull Insets insets) {
        // Overridden in subclasses
    }

    CharSequence getAppBarDefaultTitle(@Nullable MediaSource mediaSource) {
        return (mediaSource != null) ? mediaSource.getDisplayName(mActivity)
                : mActivity.getResources().getString(R.string.media_app_title);
    }

    class BasicAppBarListener extends AppBarController.AppBarListener {
        @Override
        protected void onSettingsSelection() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onSettingsSelection");
            }
            mMediaItemsRepository.getAnalyticsManager().sendViewChangedEvent(
                    AnalyticsEvent.SETTINGS_VIEW, ViewChangeEvent.SHOW);
            try {
                if (mCurrentSourceBrowserSettings != null) {
                    mCurrentSourceBrowserSettings.send();
                } else if (mCurrentSourcePreferences != null) {
                    mActivity.startActivity(mCurrentSourcePreferences);
                }
            } catch (ActivityNotFoundException | PendingIntent.CanceledException e) {
                if (Log.isLoggable(TAG, Log.ERROR)) {
                    Log.e(TAG, "onSettingsSelection " + e);
                }
            }
        }

        @Override
        protected void onEqualizerSelection() {
            Intent i = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
            // Using startActivityForResult so that the control panel app can track changes for
            // the launching package name.
            mActivity.startActivityForResult(i, 0);
        }
    }

    @CallSuper
    void onMediaSourceChanged(@Nullable MediaSource mediaSource) {
        Resources res = mActivity.getResources();
        Drawable icon = null;
        Drawable searchIcon = null;
        if (mediaSource != null) {
            // Drawables can't be shared due to the fact that the layout manager effects the
            // underlying Drawable causing artifacts when then are both "on screen"
            icon = new BitmapDrawable(res, mediaSource.getCroppedPackageIcon());
            searchIcon = new BitmapDrawable(res, mediaSource.getCroppedPackageIcon());
        }

        mAppBarController.setLogo(icon);
        mAppBarController.setHasEqualizer(mShouldShowSoundSettings);
    }

    private void onMediaBrowsingStateChanged(MediaBrowserConnector.BrowsingState newBrowsingState) {
        if (newBrowsingState == null) {
            Log.e(TAG, "Null browsing state (no media source!)");
            resetPreferencesState();
            return;
        }
        switch (newBrowsingState.mConnectionStatus) {
            case CONNECTED:
                updateSourcePreferences(newBrowsingState);
                break;
            case CONNECTING:
            case DISCONNECTING:
            case REJECTED:
            case SUSPENDED:
                resetPreferencesState();
                break;
        }
    }

    private void resetPreferencesState() {
        mCurrentSourceBrowserSettings = null;
        mCurrentSourcePreferences = null;
        mAppBarController.setHasSettings(false);
    }

    private void updateSourcePreferences(@NonNull MediaBrowserConnector.BrowsingState state) {
        PendingIntent browserPrefs = MediaBrowserViewModelImpl.getSettingsIntent(state.mBrowser);
        if (browserPrefs != null) {
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "Source: " + state.mMediaSource + " has settings intent: "
                        + browserPrefs);
            }
            mCurrentSourceBrowserSettings = browserPrefs;
            mAppBarController.setHasSettings(true);
            mAppBarController.setSettingsDistractionOptimized(true);
        } else {
            Intent prefsIntent = new Intent(Intent.ACTION_APPLICATION_PREFERENCES);
            prefsIntent.setPackage(state.mMediaSource.getPackageName());
            ResolveInfo info = mActivity.getPackageManager().resolveActivity(prefsIntent, 0);
            if (info != null && info.activityInfo != null && info.activityInfo.exported) {
                mCurrentSourcePreferences = new Intent(prefsIntent.getAction())
                        .setClassName(info.activityInfo.packageName, info.activityInfo.name);
                mAppBarController.setHasSettings(true);
                mAppBarController.setSettingsDistractionOptimized(
                        CarPackageManagerUtils.isDistractionOptimized(
                                mCarPackageManager, info.activityInfo));

                if (Log.isLoggable(TAG, Log.INFO)) {
                    Log.i(TAG, "Source: " + state.mMediaSource + " has prefs intent");
                }
            }
        }
    }
}
