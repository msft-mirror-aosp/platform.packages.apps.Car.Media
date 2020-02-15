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
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.android.car.apps.common.util.CarPackageManagerUtils;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.source.MediaSourceViewModel;
import com.android.car.media.widgets.AppBarView;

/**
 * Functionality common to content view controllers. It mainly handles the AppBar view,
 * which is common to all them.
 */
abstract class ViewControllerBase {
    private static final String TAG = "ViewControllerBase";

    private final boolean mShouldShowSoundSettings;
    private final CarPackageManagerUtils mCarPackageManagerUtils;

    final FragmentActivity mActivity;
    final int mFadeDuration;
    final View mContent;
    final AppBarView mAppBarView;
    final MediaSourceViewModel mMediaSourceVM;

    private Intent mCurrentSourcePreferences;


    ViewControllerBase(FragmentActivity activity, ViewGroup container, @LayoutRes int resource) {
        mActivity = activity;
        Resources res = mActivity.getResources();
        mFadeDuration = res.getInteger(R.integer.new_album_art_fade_in_duration);
        mShouldShowSoundSettings = res.getBoolean(R.bool.show_sound_settings);
        mCarPackageManagerUtils = CarPackageManagerUtils.getInstance(mActivity);

        LayoutInflater inflater = LayoutInflater.from(container.getContext());
        mContent = inflater.inflate(resource, container, false);

        mAppBarView = mContent.findViewById(R.id.app_bar);
        mAppBarView.setSearchSupported(false);
        mAppBarView.setHasEqualizer(false);

        container.addView(mContent);

        mMediaSourceVM = MediaSourceViewModel.get(activity.getApplication());
    }

    CharSequence getAppBarDefaultTitle(@Nullable MediaSource mediaSource) {
        return (mediaSource != null) ? mediaSource.getDisplayName()
                : mActivity.getResources().getString(R.string.media_app_title);
    }

    class BasicAppBarListener extends AppBarView.AppBarListener {
        @Override
        protected void onSettingsSelection() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onSettingsSelection");
            }
            try {
                if (mCurrentSourcePreferences != null) {
                    mActivity.startActivity(mCurrentSourcePreferences);
                }
            } catch (ActivityNotFoundException e) {
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
        String packageName = null;
        if (mediaSource != null) {
            // Drawables can't be shared due to the fact that the layout manager effects the
            // underlying Drawable causing artifacts when then are both "on screen"
            icon = new BitmapDrawable(res, mediaSource.getRoundPackageIcon());
            searchIcon = new BitmapDrawable(res, mediaSource.getRoundPackageIcon());
            packageName = mediaSource.getPackageName();
        }

        mAppBarView.setLogo(icon);
        mAppBarView.setSearchIcon(searchIcon);
        updateSourcePreferences(packageName);
    }

    // TODO(b/136274938): display the preference screen for each media service.
    private void updateSourcePreferences(@Nullable String packageName) {
        mCurrentSourcePreferences = null;
        if (packageName != null) {
            Intent prefsIntent = new Intent(Intent.ACTION_APPLICATION_PREFERENCES);
            prefsIntent.setPackage(packageName);
            ResolveInfo info = mActivity.getPackageManager().resolveActivity(prefsIntent, 0);
            if (info != null && info.activityInfo != null && info.activityInfo.exported) {
                mCurrentSourcePreferences = new Intent(prefsIntent.getAction())
                        .setClassName(info.activityInfo.packageName, info.activityInfo.name);
                mAppBarView.setSettingsDistractionOptimized(
                        mCarPackageManagerUtils.isDistractionOptimized(info.activityInfo));
            }
        }
        mAppBarView.setHasSettings(mCurrentSourcePreferences != null);
        mAppBarView.setHasEqualizer(mShouldShowSoundSettings);
    }


}
