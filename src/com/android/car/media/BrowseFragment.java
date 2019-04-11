/*
 * Copyright 2018 The Android Open Source Project
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

import static com.android.car.apps.common.FragmentUtils.checkParent;
import static com.android.car.apps.common.FragmentUtils.requireParent;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.arch.common.FutureData;
import com.android.car.media.browse.BrowseAdapter;
import com.android.car.media.common.GridSpacingItemDecoration;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.browse.MediaBrowserViewModel;
import com.android.car.media.common.source.MediaSourceViewModel;
import com.android.car.media.widgets.ViewUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/**
 * A {@link Fragment} that implements the content forward browsing experience.
 */
public class BrowseFragment extends Fragment {
    private static final String TAG = "BrowseFragment";
    private static final String TOP_MEDIA_ITEM_KEY = "top_media_item";
    private static final String SEARCH_KEY = "search_config";
    private static final String BROWSE_STACK_KEY = "browse_stack";

    private RecyclerView mBrowseList;
    private ProgressBar mProgressBar;
    private ImageView mErrorIcon;
    private TextView mErrorMessage;
    private BrowseAdapter mBrowseAdapter;
    private MediaItemMetadata mTopMediaItem;
    private String mSearchQuery;
    private int mFadeDuration;
    private int mProgressBarDelay;
    private boolean mShowSearchResults;
    private Handler mHandler = new Handler();
    private Stack<MediaItemMetadata> mBrowseStack = new Stack<>();
    private MediaBrowserViewModel.WithMutableBrowseId mMediaBrowserViewModel;
    private BrowseAdapter.Observer mBrowseAdapterObserver = new BrowseAdapter.Observer() {

        @Override
        protected void onPlayableItemClicked(MediaItemMetadata item) {
            hideKeyboard();
            getParent().onPlayableItemClicked(item);
        }

        @Override
        protected void onBrowsableItemClicked(MediaItemMetadata item) {
            navigateInto(item);
        }
    };

    /**
     * Fragment callbacks (implemented by the hosting Activity)
     */
    public interface Callbacks {
        /**
         * Method invoked when the back stack changes (for example, when the user moves up or down
         * the media tree)
         */
        void onBackStackChanged();

        /**
         * Method invoked when the user clicks on a playable item
         *
         * @param item item to be played.
         */
        void onPlayableItemClicked(MediaItemMetadata item);
    }

    /**
     * Moves the user one level up in the browse tree, if possible.
     */
    public void navigateBack() {
        if (!mBrowseStack.empty()) {
            mBrowseStack.pop();
            mMediaBrowserViewModel.search(mSearchQuery);
            mMediaBrowserViewModel.setCurrentBrowseId(getCurrentMediaItemId());
            getParent().onBackStackChanged();
        }
    }

    @NonNull
    private Callbacks getParent() {
        return requireParent(this, Callbacks.class);
    }

    /**
     * @return whether the user is at the top of the browsing stack.
     */
    public boolean isAtTopStack() {
        return mBrowseStack.isEmpty();
    }

    /**
     * Creates a new instance of this fragment.
     *
     * @param item media tree node to display on this fragment.
     * @return a fully initialized {@link BrowseFragment}
     */
    public static BrowseFragment newInstance(MediaItemMetadata item) {
        BrowseFragment fragment = new BrowseFragment();
        Bundle args = new Bundle();
        args.putParcelable(TOP_MEDIA_ITEM_KEY, item);
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * Creates a new instance of this fragment, meant to display search results.
     *
     * @return a fully initialized {@link BrowseFragment}
     */
    public static BrowseFragment newSearchInstance() {
        BrowseFragment fragment = new BrowseFragment();
        Bundle args = new Bundle();
        args.putBoolean(SEARCH_KEY, true);
        fragment.setArguments(args);
        return fragment;
    }

    public void updateSearchQuery(String query) {
        mSearchQuery = query;
        mMediaBrowserViewModel.search(query);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle arguments = getArguments();
        if (arguments != null) {
            mTopMediaItem = arguments.getParcelable(TOP_MEDIA_ITEM_KEY);
            mShowSearchResults = arguments.getBoolean(SEARCH_KEY, false);
        }
        if (savedInstanceState != null) {
            List<MediaItemMetadata> savedStack =
                    savedInstanceState.getParcelableArrayList(BROWSE_STACK_KEY);
            mBrowseStack.clear();
            if (savedStack != null) {
                mBrowseStack.addAll(savedStack);
            }
        }

        // Get the MediaBrowserViewModel tied to the lifecycle of this fragment, but using the
        // MediaSourceViewModel of the activity. This means the media source is consistent across
        // all fragments, but the fragment contents themselves will vary
        // (e.g. between different browse tabs, search)
        mMediaBrowserViewModel = MediaBrowserViewModel.Factory.getInstanceWithMediaBrowser(
                ViewModelProviders.of(this),
                MediaSourceViewModel.get(
                        requireActivity().getApplication()).getConnectedMediaBrowser());
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, final ViewGroup container,
            Bundle savedInstanceState) {
        int viewId = mShowSearchResults ? R.layout.fragment_search : R.layout.fragment_browse;
        View view = inflater.inflate(viewId, container, false);
        mProgressBar = view.findViewById(R.id.loading_spinner);
        mProgressBarDelay = view.getContext().getResources()
                .getInteger(R.integer.progress_indicator_delay);
        mBrowseList = view.findViewById(R.id.browse_list);
        mErrorIcon = view.findViewById(R.id.error_icon);
        mErrorMessage = view.findViewById(R.id.error_message);
        mFadeDuration = view.getContext().getResources().getInteger(
                R.integer.new_album_art_fade_in_duration);
        int numColumns = view.getContext().getResources().getInteger(R.integer.num_browse_columns);
        GridLayoutManager gridLayoutManager = new GridLayoutManager(getContext(), numColumns);

        mBrowseList.setLayoutManager(gridLayoutManager);
        mBrowseList.addItemDecoration(new GridSpacingItemDecoration(
                getResources().getDimensionPixelSize(R.dimen.grid_item_spacing),
                getResources().getDimensionPixelSize(R.dimen.grid_item_margin_x),
                getResources().getDimensionPixelSize(R.dimen.grid_item_margin_x)
        ));

        mBrowseAdapter = new BrowseAdapter(mBrowseList.getContext());
        mBrowseList.setAdapter(mBrowseAdapter);
        mBrowseAdapter.registerObserver(mBrowseAdapterObserver);

        if (savedInstanceState == null) {
            mMediaBrowserViewModel.search(mSearchQuery);
            mMediaBrowserViewModel.setCurrentBrowseId(getCurrentMediaItemId());
        }
        mMediaBrowserViewModel.rootBrowsableHint().observe(this, hint ->
                mBrowseAdapter.setRootBrowsableViewType(hint));
        mMediaBrowserViewModel.rootPlayableHint().observe(this, hint ->
                mBrowseAdapter.setRootPlayableViewType(hint));
        LiveData<FutureData<List<MediaItemMetadata>>> mediaItems = mShowSearchResults
                ? mMediaBrowserViewModel.getSearchedMediaItems()
                : mMediaBrowserViewModel.getBrowsedMediaItems();
        mediaItems.observe(getViewLifecycleOwner(), futureData ->
        {
            boolean isLoading = futureData.isLoading();
            List<MediaItemMetadata> items = futureData.getData();
            if (isLoading) {
                startLoadingIndicator();
                return;
            }
            stopLoadingIndicator();
            mBrowseAdapter.submitItems(getCurrentMediaItem(), items);
            if (items == null) {
                mErrorMessage.setText(R.string.unknown_error);
                ViewUtils.hideViewAnimated(mBrowseList, mFadeDuration);
                ViewUtils.showViewAnimated(mErrorMessage, mFadeDuration);
                ViewUtils.showViewAnimated(mErrorIcon, mFadeDuration);
            } else if (items.isEmpty()) {
                mErrorMessage.setText(R.string.nothing_to_play);
                ViewUtils.hideViewAnimated(mBrowseList, mFadeDuration);
                ViewUtils.hideViewAnimated(mErrorIcon, mFadeDuration);
                ViewUtils.showViewAnimated(mErrorMessage, mFadeDuration);
            } else {
                ViewUtils.showViewAnimated(mBrowseList, mFadeDuration);
                ViewUtils.hideViewAnimated(mErrorIcon, mFadeDuration);
                ViewUtils.hideViewAnimated(mErrorMessage, mFadeDuration);
            }
        });
        return view;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        checkParent(this, Callbacks.class);
    }

    private Runnable mProgressIndicatorRunnable = new Runnable() {
        @Override
        public void run() {
            ViewUtils.showViewAnimated(mProgressBar, mFadeDuration);
        }
    };

    private void startLoadingIndicator() {
        // Display the indicator after a certain time, to avoid flashing the indicator constantly,
        // even when performance is acceptable.
        mHandler.postDelayed(mProgressIndicatorRunnable, mProgressBarDelay);
    }

    private void stopLoadingIndicator() {
        mHandler.removeCallbacks(mProgressIndicatorRunnable);
        ViewUtils.hideViewAnimated(mProgressBar, mFadeDuration);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        ArrayList<MediaItemMetadata> stack = new ArrayList<>(mBrowseStack);
        outState.putParcelableArrayList(BROWSE_STACK_KEY, stack);
    }

    private void navigateInto(MediaItemMetadata item) {
        hideKeyboard();
        mBrowseStack.push(item);
        mMediaBrowserViewModel.setCurrentBrowseId(item.getId());
        getParent().onBackStackChanged();
    }

    /**
     * @return the current item being displayed
     */
    @Nullable
    MediaItemMetadata getCurrentMediaItem() {
        if (mBrowseStack.isEmpty()) {
            return mTopMediaItem;
        } else {
            return mBrowseStack.lastElement();
        }
    }

    @Nullable
    private String getCurrentMediaItemId() {
        MediaItemMetadata currentItem = getCurrentMediaItem();
        return currentItem != null ? currentItem.getId() : null;
    }

    private void hideKeyboard() {
        InputMethodManager in =
                (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        in.hideSoftInputFromWindow(getView().getWindowToken(), 0);

    }
}
