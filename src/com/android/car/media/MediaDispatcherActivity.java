package com.android.car.media;

import static android.car.media.CarMediaIntents.EXTRA_MEDIA_COMPONENT;
import static android.car.media.CarMediaManager.MEDIA_SOURCE_MODE_BROWSE;

import android.car.Car;
import android.car.media.CarMediaIntents;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.FragmentActivity;

import com.android.car.media.common.source.CarMediaManagerHelper;
import com.android.car.media.common.source.MediaSource;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * A trampoline activity that handles the {@link Car#CAR_INTENT_ACTION_MEDIA_TEMPLATE} implicit
 * intent, and fires up either the Media Center's {@link MediaActivity}, or the specialized
 * application if the selected media source is custom (e.g. the Radio app).
 */
public class MediaDispatcherActivity extends FragmentActivity {

    private static final String TAG = "MediaDispatcherActivity";
    private static Set<String> sCustomMediaComponents = null;

    static final String KEY_MEDIA_ID = "com.android.car.media.intent.extra.MEDIA_ID";

    static boolean isCustomMediaSource(Resources res, @Nullable MediaSource source) {
        if (sCustomMediaComponents == null) {
            sCustomMediaComponents = new HashSet<>();
            sCustomMediaComponents.addAll(
                    Arrays.asList(res.getStringArray(
                        com.android.car.media.common.R.array.custom_media_packages)));
        }

        return (source != null)
                && sCustomMediaComponents.contains(
                        source.getBrowseServiceComponentName().flattenToString());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dispatch(this, getIntent(), CarMediaManagerHelper.getInstance(this));
        finish();
    }

    /** !!VisibleForTesting!! */
    @VisibleForTesting
    public static void dispatch(Context ctx, Intent intent, CarMediaManagerHelper helper) {
        String action = null;
        String componentName = null;
        String mediaId = null;
        if (intent != null) {
            action = intent.getAction();
            componentName = intent.getStringExtra(EXTRA_MEDIA_COMPONENT);
            mediaId = intent.getStringExtra(KEY_MEDIA_ID);
        }

        if (Log.isLoggable(TAG, Log.INFO)) {
            Log.i(TAG, "onCreate action: " + action + " component: " + componentName);
        }

        MediaSource mediaSrc = null;
        if (CarMediaIntents.ACTION_MEDIA_TEMPLATE.equals(action)) {
            if (componentName != null) {
                ComponentName mediaSrcComp = ComponentName.unflattenFromString(componentName);
                if (mediaSrcComp != null) {
                    mediaSrc = MediaSource.create(ctx, mediaSrcComp);
                }
            }
        }

        // Retrieve the current source if none was set, otherwise save the given source.
        if (mediaSrc == null) {
            mediaSrc = helper.getAudioSource(MEDIA_SOURCE_MODE_BROWSE).getValue();
        } else {
            helper.setPrimaryMediaSource(mediaSrc, MEDIA_SOURCE_MODE_BROWSE);
        }

        Intent newIntent = null;
        if ((mediaSrc != null) && isCustomMediaSource(ctx.getResources(), mediaSrc)) {
            // Launch custom app (e.g. Radio)
            String srcPackage = mediaSrc.getPackageName();
            newIntent = ctx.getPackageManager().getLaunchIntentForPackage(srcPackage);
            if (newIntent != null) {
                newIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                newIntent.putExtra(EXTRA_MEDIA_COMPONENT, componentName);
            }
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Getting launch intent for package : " + srcPackage + (newIntent != null
                        ? " succeeded" : " failed"));
            }
        }

        // Launch media center only if there is a media source
        if ((newIntent == null) && (mediaSrc != null)) {
            newIntent = MediaActivity.createMediaActivityIntent(ctx, mediaSrc, mediaId);
        }

        if (newIntent != null) {
            ctx.startActivity(newIntent);
        } else {
            Log.e(TAG, "No intent to launch, mediaSrc: " + mediaSrc);
        }
    }
}
