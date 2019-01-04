package com.android.car.media;

import static com.android.car.arch.common.LiveDataFunctions.pair;
import static com.android.car.arch.common.LiveDataFunctions.split;

import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelProviders;

import com.android.car.media.common.browse.MediaBrowserViewModel;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.source.MediaSourceViewModel;
import com.android.car.media.widgets.ViewUtils;

/**
 * Empty fragment to show while we are loading content
 */
public class EmptyFragment extends Fragment {
    private static final String TAG = "EmptyFragment";

    private ProgressBar mProgressBar;
    private ImageView mErrorIcon;
    private TextView mErrorMessage;

    private int mProgressBarDelay;
    private Handler mHandler = new Handler();
    private int mFadeDuration;
    private Runnable mProgressIndicatorRunnable = new Runnable() {
        @Override
        public void run() {
            ViewUtils.showViewAnimated(mProgressBar, mFadeDuration);
        }
    };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, final ViewGroup container,
            Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_empty, container, false);
        mProgressBar = view.findViewById(R.id.loading_spinner);
        mProgressBarDelay = requireContext().getResources()
                .getInteger(R.integer.progress_indicator_delay);
        mFadeDuration = requireContext().getResources().getInteger(
                R.integer.new_album_art_fade_in_duration);
        mErrorIcon = view.findViewById(R.id.error_icon);
        mErrorMessage = view.findViewById(R.id.error_message);

        ViewModelProvider viewModelProvider = ViewModelProviders.of(requireActivity());
        MediaSourceViewModel mediaSourceViewModel =
                viewModelProvider.get(MediaSourceViewModel.class);
        MediaBrowserViewModel mediaBrowserViewModel =
                MediaBrowserViewModel.Factory.getInstanceWithMediaBrowser(viewModelProvider,
                        mediaSourceViewModel.getConnectedMediaBrowser());
        LiveData<MediaBrowserViewModel.BrowseState> browseState =
                mediaBrowserViewModel.getBrowseState();
        LiveData<MediaSource> selectedMediaSource =
                mediaSourceViewModel.getPrimaryMediaSource();
        pair(browseState, selectedMediaSource)
                .observe(getViewLifecycleOwner(), split(this::setState));
        return view;
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mProgressIndicatorRunnable);
    }

    /**
     * Updates the state of this fragment
     *
     * @param state       browsing state to display
     * @param mediaSource media source currently being browsed
     */
    private void setState(@NonNull MediaBrowserViewModel.BrowseState state,
            @Nullable MediaSource mediaSource) {
        mHandler.removeCallbacks(mProgressIndicatorRunnable);
        if (this.getView() != null) {
            update(state, mediaSource);
        }
    }

    private void update(@NonNull MediaBrowserViewModel.BrowseState state,
            @Nullable MediaSource mediaSource) {
        switch (state) {
            case LOADING:
                // Display the indicator after a certain time, to avoid flashing the indicator
                // constantly, even when performance is acceptable.
                mHandler.postDelayed(mProgressIndicatorRunnable, mProgressBarDelay);
                mErrorIcon.setVisibility(View.GONE);
                mErrorMessage.setVisibility(View.GONE);
                break;
            case ERROR:
                mProgressBar.setVisibility(View.GONE);
                mErrorIcon.setVisibility(View.VISIBLE);
                mErrorMessage.setVisibility(View.VISIBLE);
                mErrorMessage.setText(requireContext().getString(
                        R.string.cannot_connect_to_app,
                        mediaSource != null
                                ? mediaSource.getName()
                                : requireContext().getString(
                                        R.string.unknown_media_provider_name)));
                break;
            case EMPTY:
                mProgressBar.setVisibility(View.GONE);
                mErrorIcon.setVisibility(View.GONE);
                mErrorMessage.setVisibility(View.VISIBLE);
                mErrorMessage.setText(requireContext().getString(R.string.nothing_to_play));
                break;
            case LOADED:
                Log.d(TAG, "Updated with LOADED state, ignoring.");
                // Do nothing, this fragment is about to be removed
                break;
            default:
                // Fail fast on any other state.
                throw new IllegalStateException("Invalid state for this fragment: " + state);
        }
    }
}
