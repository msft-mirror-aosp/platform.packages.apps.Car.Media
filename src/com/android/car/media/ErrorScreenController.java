package com.android.car.media;

import android.app.PendingIntent;
import android.car.content.pm.CarPackageManager;
import android.content.res.Resources;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.apps.common.util.IntentUtils;
import com.android.car.media.common.PlaybackErrorViewController;
import com.android.car.media.common.source.MediaSource;
import com.android.car.ui.FocusArea;

/**
 * A view controller that displays the playback state error iif there is no browse tree.
 */
public class ErrorScreenController extends ViewControllerBase {

    private final PlaybackErrorViewController mPlaybackErrorViewController;
    private final FocusArea mFocusArea;
    private PendingIntent mPendingIntent;
    private boolean mCanAutoLaunch;

    ErrorScreenController(FragmentActivity activity, CarPackageManager carPackageManager,
            ViewGroup container) {
        super(activity, carPackageManager, container, R.layout.fragment_error);

        mPlaybackErrorViewController = new PlaybackErrorViewController(mContent);
        mFocusArea = mContent.findViewById(R.id.focus_area);

        MediaActivity.ViewModel viewModel =
                new ViewModelProvider(activity).get(MediaActivity.ViewModel.class);
        viewModel.getMiniControlsVisible().observe(activity, this::onPlaybackControlsChanged);
    }

    @Override
    void onMediaSourceChanged(@Nullable MediaSource mediaSource) {
        super.onMediaSourceChanged(mediaSource);

        mAppBarController.setListener(new BasicAppBarListener());
        mAppBarController.setTitle(getAppBarDefaultTitle(mediaSource));

        mPlaybackErrorViewController.hideErrorNoAnim();
    }

    public void setError(String message, String label, PendingIntent pendingIntent,
            boolean canAutoLaunch, boolean distractionOptimized) {
        mPendingIntent = pendingIntent;
        mCanAutoLaunch = canAutoLaunch;
        mPlaybackErrorViewController.setError(message, label, pendingIntent, distractionOptimized);
        maybeLaunchIntent();
    }

    /** Should be called when the activity is resumed. */
    public void onResume() {
        maybeLaunchIntent();
    }

    private void maybeLaunchIntent() {
        if (mCanAutoLaunch && mPendingIntent != null) {
            IntentUtils.sendIntent(mPendingIntent);
        }
    }

    void onPlaybackControlsChanged(boolean visible) {
        int leftPadding = mFocusArea.getPaddingLeft();
        int topPadding = mFocusArea.getPaddingTop();
        int rightPadding = mFocusArea.getPaddingRight();
        Resources res = mActivity.getResources();
        int bottomPadding = visible
                ? res.getDimensionPixelOffset(R.dimen.browse_fragment_bottom_padding) : 0;
        mFocusArea.setPadding(leftPadding, topPadding, rightPadding, bottomPadding);
        mFocusArea.setHighlightPadding(leftPadding, topPadding, rightPadding, bottomPadding);
        // Set the bottom offset to bottomPadding regardless of mSetFocusAreaHighlightBottom so that
        // RotaryService can find the correct target when the user nudges the rotary controller.
        mFocusArea.setBoundsOffset(leftPadding, topPadding, rightPadding, bottomPadding);
    }
}
