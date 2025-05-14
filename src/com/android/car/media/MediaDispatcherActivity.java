package com.android.car.media;

import static android.car.media.CarMediaIntents.EXTRA_MEDIA_COMPONENT;
import static android.car.media.CarMediaIntents.EXTRA_SEARCH_QUERY;
import static android.car.media.CarMediaManager.MEDIA_SOURCE_MODE_BROWSE;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;

import static androidx.car.app.mediaextensions.MediaIntentExtras.ACTION_MEDIA_TEMPLATE_V2;
import static androidx.car.app.mediaextensions.MediaIntentExtras.EXTRA_KEY_MEDIA_ID;
import static androidx.car.app.mediaextensions.MediaIntentExtras.EXTRA_KEY_SEARCH_ACTION;
import static androidx.car.app.mediaextensions.MediaIntentExtras.EXTRA_VALUE_NO_SEARCH_ACTION;

import android.car.Car;
import android.car.media.CarMediaIntents;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
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
    private static final String CAL_MEDIA_ACTIVITY_COMPONENT =
            "androidx.car.app.media.CalMediaActivityComponent";
    public static final String FEATURE_CAR_APP_LIBRARY_MEDIA =
            "android.software.car.templates_host.media";

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
        String searchQuery = null;
        int searchAction = EXTRA_VALUE_NO_SEARCH_ACTION;
        if (intent != null) {
            action = intent.getAction();
            componentName = intent.getStringExtra(EXTRA_MEDIA_COMPONENT);
            mediaId = intent.getStringExtra(EXTRA_KEY_MEDIA_ID);
            searchQuery = intent.getStringExtra(EXTRA_SEARCH_QUERY);
            searchAction = intent.getIntExtra(EXTRA_KEY_SEARCH_ACTION,
                    EXTRA_VALUE_NO_SEARCH_ACTION);
        }

        if (Log.isLoggable(TAG, Log.INFO)) {
            Log.i(TAG, "onCreate action: " + action + " component: " + componentName);
        }

        MediaSource mediaSrc = null;
        if (CarMediaIntents.ACTION_MEDIA_TEMPLATE.equals(action)
                || ACTION_MEDIA_TEMPLATE_V2.equals(action)) {
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
            if (maybeLaunchCalComponentForMediaSource(ctx, mediaSrc)) return;
            Log.d(TAG, "starting MBS for " + mediaSrc.getPackageName());
            newIntent = MediaActivity.createMediaActivityIntent(ctx, mediaSrc, mediaId,
                    searchQuery, searchAction);
        }

        if (newIntent != null) {
            ctx.startActivity(newIntent);
        } else {
            Log.e(TAG, "No intent to launch, mediaSrc: " + mediaSrc);
        }
    }

    private static boolean maybeLaunchCalComponentForMediaSource(
            Context ctx, MediaSource mediaSrc) {
        if (mediaSrc == null || mediaSrc.getBrowseServiceComponentName() == null) return false;
        PackageManager packageManager = ctx.getPackageManager();
        // Check for CAL Activity as MBS replacement only on systems that support the
        // CAL Media feature
        if (packageManager.hasSystemFeature(FEATURE_CAR_APP_LIBRARY_MEDIA)) {
            try {
                // Check if the metadata of the MBS contains a CarAppActivity or
                // Trampoline Activity component and enable and launch it
                ServiceInfo serviceInfo = packageManager.getServiceInfo(
                        mediaSrc.getBrowseServiceComponentName(),
                        PackageManager.GET_META_DATA);
                Bundle metaData = serviceInfo.metaData;
                if (metaData != null
                        && metaData.containsKey(CAL_MEDIA_ACTIVITY_COMPONENT)) {
                    String calTrampoline = metaData.getString(
                            CAL_MEDIA_ACTIVITY_COMPONENT);
                    Log.i(TAG, "Found CAL Activity Trampoline for MBS "
                            + mediaSrc.getBrowseServiceComponentName() + " as "
                            + calTrampoline);
                    Intent calAppIntent = new Intent();
                    if (calTrampoline == null
                            || ComponentName.unflattenFromString(calTrampoline) == null) {
                        Log.i(TAG, "Application set a null CarAppActivity Component for "
                                + mediaSrc.getBrowseServiceComponentName());
                        return false;
                    }
                    calAppIntent.setComponent(
                            ComponentName.unflattenFromString(calTrampoline));
                    ResolveInfo calResolveInfo = packageManager.resolveActivity(
                            calAppIntent, PackageManager.MATCH_DISABLED_COMPONENTS);
                    if (calResolveInfo != null) {
                        Log.i(TAG, "Enabling and launching component: "
                                + calAppIntent.getComponent());
                        packageManager.setComponentEnabledSetting(
                                calAppIntent.getComponent(),
                                COMPONENT_ENABLED_STATE_ENABLED, DONT_KILL_APP
                        );
                        ctx.startActivity(calAppIntent);
                        return true;
                    } else {
                        Log.i(TAG, "Activity not found: "
                                + calAppIntent.getComponent());
                    }
                } else {
                    Log.i(TAG, "Application did not set CarAppActivity component for "
                            + mediaSrc.getBrowseServiceComponentName());
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Exception trying to find MBS service info for "
                        + mediaSrc.getPackageName());
            }
        }
        return false;
    }
}
