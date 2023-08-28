/*
 * Copyright (C) 2020 The Android Open Source Project
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
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS;

import static com.android.car.apps.common.util.ViewUtils.showHideViewAnimated;
import static com.android.car.media.common.source.MediaBrowserConnector.ConnectionStatus.CONNECTED;
import static com.android.car.ui.utils.ViewUtils.LazyLayoutView;

import android.car.content.pm.CarPackageManager;
import android.content.Context;
import android.support.v4.media.MediaBrowserCompat;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.OnScrollListener;

import com.android.car.apps.common.util.FutureData;
import com.android.car.apps.common.util.ViewUtils.ViewAnimEndListener;
import com.android.car.media.BrowseStack.BrowseEntryType;
import com.android.car.media.MediaActivity.Mode;
import com.android.car.media.browse.LimitedBrowseAdapter;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.browse.MediaBrowserViewModelImpl;
import com.android.car.media.common.browse.MediaItemsRepository;
import com.android.car.media.common.source.MediaBrowserConnector.BrowsingState;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.extensions.analytics.event.AnalyticsEvent;
import com.android.car.media.extensions.analytics.event.BrowseChangeEvent;
import com.android.car.media.widgets.AppBarController;
import com.android.car.ui.FocusParkingView;
import com.android.car.ui.baselayout.Insets;
import com.android.car.ui.recyclerview.CarUiRecyclerView;
import com.android.car.ui.toolbar.NavButtonMode;
import com.android.car.ui.toolbar.SearchConfig;
import com.android.car.ui.toolbar.SearchMode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Controls the views of the {@link MediaActivity}.
 * TODO: finish moving control code out of MediaActivity (b/179292809).
 */
public class MediaActivityController extends ViewControllerBase {

    private static final String TAG = "MediaActivityCtr";

    private final MediaItemsRepository mMediaItemsRepository;
    private final Callbacks mCallbacks;
    private final ViewGroup mBrowseArea;
    private final FocusParkingView mFpv;
    private Insets mCarUiInsets;
    private boolean mPlaybackControlsVisible;

    // Entries whose controller should be destroyed once their view is hidden.
    private final Map<View, BrowseStack.BrowseEntry> mEntriesToDestroy = new HashMap<>();

    private final RecyclerView mToolbarSearchResultsView;

    /**
     * Stores the reference to {@link MediaActivity.ViewModel#getBrowseStack}.
     * Updated in {@link #onMediaSourceChanged}.
     */
    private BrowseStack mBrowseStack;

    private int mRootBrowsableHint;
    private int mRootPlayableHint;
    private boolean mBrowseTreeHasChildrenList;
    private boolean mAcceptTabSelection = true;

    private String mPendingMediaId;

    /**
     * Media items to display as tabs. If null, it means we haven't finished loading them yet. If
     * empty, it means there are no tabs to show
     */
    @Nullable
    private List<MediaItemMetadata> mTopItems;

    private final Observer<BrowsingState> mMediaBrowsingObserver =
            this::onMediaBrowsingStateChanged;

    private final NowPlayingController mNowPlayingController;
    private final NowPlayingController.NowPlayingListener mNowPlayingListener;

    /**
     * Callbacks (implemented by the hosting Activity)
     */
    public interface Callbacks {

        /** Invoked when the user clicks on a browsable item. */
        void onPlayableItemClicked(@NonNull MediaItemMetadata item);

        /** Invoked when a user clicks on mini player in empty browse view */
        void onBrowseEmptyListPlayItemClicked();

        /** Called once the list of the root node's children has been loaded. */
        void onRootLoaded();

        /** Called when switching to pbv without changing playback content*/
        void openPlaybackView();

        /** Returns the activity. */
        FragmentActivity getActivity();

        /** Activates the given mode. */
        void changeMode(Mode mode);

        /** Switches to the given source. */
        void changeSource(MediaSource source, MediaItemMetadata mediaItem);

        /** Returns whether the queue should be visible. */
        boolean getQueueVisible();

        /** Saves whether the queue should be visible. */
        void setQueueVisible(boolean visible);
    }

    private static OnScrollListener createWideKeyboardSearchResultListener(
            RecyclerView toolbarSearchResultsView, MediaItemsRepository mediaItemsRepository) {
        return new OnScrollListener() {
            List<String> mPrevVisible = new ArrayList<>();

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                sendScrollEvent(dx != 0 || dy != 0, false);
            }

            private void sendScrollEvent(boolean fromScroll, boolean canKeyboardCover) {
                LimitedBrowseAdapter limitedBrowseAdapter =
                        (LimitedBrowseAdapter) toolbarSearchResultsView.getAdapter();
                int currFirst = limitedBrowseAdapter.findFirstVisibleItemIndex();
                int currLast = limitedBrowseAdapter.findLastVisibleItemIndex(canKeyboardCover);
                mPrevVisible = AnalyticsHelper.sendScrollEvent(mediaItemsRepository, null,
                        mPrevVisible, limitedBrowseAdapter.getItems(), currFirst, currLast,
                        fromScroll);
            }
        };
    }


    /**
     * Moves the user one level up in the browse/search tree. Returns whether that was possible.
     */
    private boolean navigateBack() {
        boolean result = false;
        if (isStacked()) {
            // Clean up previous
            hideAndDestroyStackEntry(mBrowseStack.pop());

            // Show the parent (if any)
            showCurrentNode(true);

            updateAppBar();
            result = true;
        }
        return result;
    }

    private FragmentActivity getActivity() {
        return mCallbacks.getActivity();
    }

    /** Returns true when at least one entry can be removed from the stack. */
    private boolean isStacked() {
        List<BrowseStack.BrowseEntry> entries = mBrowseStack.getEntries();
        if (entries.size() <= 1) {
            // The root can't be removed from the stack.
            return false;
        } else if (entries.get(1).mType == BrowseEntryType.TREE_TAB) {
            // Tabs can't be removed from the stack
            return entries.size() > 2;
        }
        return true;
    }

    @Nullable
    private MediaItemMetadata getSelectedTab() {
        List<BrowseStack.BrowseEntry> entries = mBrowseStack.getEntries();
        if (2 <= entries.size() && entries.get(1).mType == BrowseEntryType.TREE_TAB) {
            return entries.get(1).mItem;
        }
        return null;
    }

    /** Destroys controllers but should NOT change the stack for the source. */
    private void clearMediaSource() {
        for (BrowseStack.BrowseEntry entry : mBrowseStack.getEntries()) {
            entry.destroyController();
        }
        mBrowseTreeHasChildrenList = false;
        if (mToolbarSearchResultsView != null) {
            mToolbarSearchResultsView.setAdapter(null);
        }
    }

    /**
     * Clears search state, removes any UI elements from previous results.
     */
    @Override
    void onMediaSourceChanged(@Nullable MediaSource mediaSource) {
        super.onMediaSourceChanged(mediaSource);

        mBrowseStack = mViewModel.getBrowseStack();

        updateAppBar();
    }

    private void onMediaBrowsingStateChanged(BrowsingState newBrowsingState) {
        if (newBrowsingState == null) {
            Log.e(TAG, "Null browsing state (no media source!)");
            return;
        }
        switch (newBrowsingState.mConnectionStatus) {
            case CONNECTING:
                break;
            case CONNECTED:
                MediaBrowserCompat browser = newBrowsingState.mBrowser;
                mRootBrowsableHint = MediaBrowserViewModelImpl.getRootBrowsableHint(browser);
                mRootPlayableHint = MediaBrowserViewModelImpl.getRootPlayableHint(browser);

                boolean canSearch = MediaBrowserViewModelImpl.getSupportsSearch(browser);
                mAppBarController.setSearchSupported(canSearch);
                if (mBrowseStack.size() <= 0) {
                    String rootId = newBrowsingState.mBrowser.getRoot();
                    mBrowseStack.pushRoot(BrowseViewController.newRootController(
                            rootId, mBrowseCallbacks, mBrowseArea, mMediaItemsRepository));
                }
                showCurrentNode(true);

                if (mPendingMediaId != null) {
                    navigateTo(mPendingMediaId);
                }
                break;
            case DISCONNECTING:
            case REJECTED:
            case SUSPENDED:
                clearMediaSource();
                break;
        }

        mViewModel.saveBrowsedMediaSource(newBrowsingState.mMediaSource);
    }


    MediaActivityController(Callbacks callbacks, MediaActivity.ViewModel viewModel,
            CarPackageManager carPackageManager, ViewGroup container, ViewGroup playbackContainer) {
        super(callbacks.getActivity(), carPackageManager, container,
                R.layout.fragment_browse);

        FragmentActivity activity = callbacks.getActivity();
        mCallbacks = callbacks;
        mMediaItemsRepository = viewModel.getMediaItemsRepository(MEDIA_SOURCE_MODE_BROWSE);
        mBrowseStack = mViewModel.getBrowseStack();
        mBrowseArea = mContent.requireViewById(R.id.browse_content_area);
        mFpv = activity.requireViewById(R.id.fpv);

        LayoutInflater inflater = LayoutInflater.from(playbackContainer.getContext());
        View playbackView = inflater.inflate(R.layout.fragment_playback, playbackContainer, false);
        playbackContainer.addView(playbackView);
        mNowPlayingController = new NowPlayingController(callbacks, playbackView,
                viewModel.getPlaybackViewModel(MEDIA_SOURCE_MODE_PLAYBACK),
                viewModel.getMediaItemsRepository(MEDIA_SOURCE_MODE_PLAYBACK));

        mAppBarController.setListener(mAppBarListener);
        mAppBarController.setSearchQuery(mViewModel.getSearchQuery());
        if (mAppBarController.getSearchCapabilities().canShowSearchResultsView()) {
            // TODO(b/180441965) eliminate the need to create a different view
            mToolbarSearchResultsView = new RecyclerView(activity);
            mToolbarSearchResultsView.addOnScrollListener(createWideKeyboardSearchResultListener(
                    mToolbarSearchResultsView, mMediaItemsRepository));

            ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            mToolbarSearchResultsView.setLayoutParams(params);
            mToolbarSearchResultsView.setLayoutManager(new LinearLayoutManager(activity));
            mToolbarSearchResultsView.setBackground(
                    activity.getDrawable(R.drawable.car_ui_ime_wide_screen_background));

            mAppBarController.setSearchConfig(SearchConfig.builder()
                    .setSearchResultsView(mToolbarSearchResultsView)
                    .build());
        } else {
            mToolbarSearchResultsView = null;
        }

        updateAppBar();

        // Observe forever ensures the caches are destroyed even while the activity isn't resumed.
        mMediaItemsRepository.getBrowsingState().observeForever(mMediaBrowsingObserver);

        mViewModel.getBrowsedMediaSource().observeForever(future -> {
            onMediaSourceChanged(future.isLoading() ? null : future.getData());
        });

        mMediaItemsRepository.getRootMediaItems().observe(activity, this::onRootMediaItemsUpdate);
        mViewModel.getMiniControlsVisible().observe(activity, this::onPlaybackControlsChanged);

        mNowPlayingListener = (source, mediaItem) -> {
            if (source == null || mediaItem == null) {
                Log.e(TAG, "goToMediaItem error S: " + source + " MI: " + mediaItem);
                return;
            } else if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "goToMediaItem S: " + source + " MI: " + mediaItem);
            }

            mCallbacks.changeMode(Mode.BROWSING);

            if (!Objects.equals(source, mViewModel.getMediaSourceValue())) {
                mCallbacks.changeSource(source, mediaItem);
            } else {
                navigateTo(mediaItem);
            }
        };
        mNowPlayingController.setListener(mNowPlayingListener);
    }

    private BrowseViewController recreateController(BrowseStack.BrowseEntry entry) {
        switch (entry.mType) {
            case TREE_ROOT:
                return BrowseViewController.newRootController(mMediaItemsRepository.getRootId(),
                        mBrowseCallbacks, mBrowseArea, mMediaItemsRepository);
            case SEARCH_RESULTS:
                BrowseViewController result = BrowseViewController.newSearchResultsController(
                        mBrowseCallbacks, mBrowseArea, mMediaItemsRepository);
                result.updateSearchQuery(mViewModel.getSearchQuery());
                return result;
            default:
                if (entry.mItem == null) {
                    Log.e(TAG, "Can't recreate controller for a null item!");
                    return null;
                }
                return BrowseViewController.newBrowseController(
                        mBrowseCallbacks, mBrowseArea, entry.mItem,
                        mMediaItemsRepository, mRootBrowsableHint, mRootPlayableHint);
        }
    }

    void onDestroy() {
        mMediaItemsRepository.getBrowsingState().removeObserver(mMediaBrowsingObserver);
        for (BrowseStack.BrowseEntry entry : mBrowseStack.getEntries()) {
            entry.destroyController();
        }
        if (mToolbarSearchResultsView != null) {
            mToolbarSearchResultsView.setAdapter(null);
        }
    }

    private final AppBarController.AppBarListener mAppBarListener = new BasicAppBarListener() {
        @Override
        public void onTabSelected(MediaItemMetadata item) {
            if (mAcceptTabSelection && (item != null) && (item != getSelectedTab())) {
                if (item.getId() != null) {
                    mMediaItemsRepository.getAnalyticsManager()
                            .sendMediaClickedEvent(item.getId(), AnalyticsEvent.BROWSE_TABS);
                }
                // Clear the entire stack, including search and links.
                hideAndDestroyStackEntries(mBrowseStack.removeAllEntriesExceptRoot());
                mBrowseStack.insertRootTab(item, createControllerForItem(item));
                showCurrentNode(true);
                updateAppBar();
            }
        }

        @Override
        public void onSearchSelection() {
            showSearchResults();
        }

        @Override
        public void onSearch(String query) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onSearch: " + query);
            }
            mViewModel.setSearchQuery(query);

            BrowseStack.BrowseEntry entry = mBrowseStack.peek();
            if ((entry != null) && (entry.getController() != null)) {
                BrowseViewController controller = entry.getController();
                controller.updateSearchQuery(query);
            } else {
                Log.e(TAG, "onSearch needs entry and controller!! " + entry);
            }
        }
    };

    private final BrowseViewController.Callbacks mBrowseCallbacks =
            new BrowseViewController.Callbacks() {
        @Override
        public void onPlayableItemClicked(@NonNull MediaItemMetadata item) {
            hideKeyboard();
            mCallbacks.onPlayableItemClicked(item);
        }

        @Override
        public void goToMediaItem(@NonNull MediaItemMetadata item) {
            hideKeyboard();
            navigateTo(item);
        }

        @Override
        public void onBrowseEmptyListPlayItemClicked() {
            mCallbacks.onBrowseEmptyListPlayItemClicked();
        }

        @Override
        public void openPlaybackView() {
            hideKeyboard();
            mCallbacks.openPlaybackView();
        }

        @Override
        public void onChildrenNodesRemoved(@NonNull BrowseViewController controller,
                @NonNull Collection<MediaItemMetadata> removedNodes) {
            List<BrowseStack.BrowseEntry> entries =
                    mBrowseStack.removeObsoleteEntries(controller, removedNodes);
            if (!entries.isEmpty()) {
                hideAndDestroyStackEntries(entries);
                showCurrentNode(true);
                updateAppBar();
            }
        }

        @Override
        public FragmentActivity getActivity() {
            return mCallbacks.getActivity();
        }

        @Override
        public void hideKeyboard() {
            MediaActivityController.this.hideKeyboard();
        }
    };

    private final ViewAnimEndListener mViewAnimEndListener = view -> {
        BrowseStack.BrowseEntry toDestroy = mEntriesToDestroy.remove(view);
        if (toDestroy != null) {
            toDestroy.destroyController();
        }
    };

    boolean onBackPressed() {
        boolean success = navigateBack();
        if (success) {
            // When the back button is pressed, if a CarUiRecyclerView shows up and it's in rotary
            // mode, restore focus in the CarUiRecyclerView.
            restoreFocusInCurrentNode();
        }
        return success;
    }

    boolean browseTreeHasChildrenList() {
        return mBrowseTreeHasChildrenList;
    }

    private BrowseEntryType getNextEntryType(@NonNull MediaItemMetadata item) {
        BrowseViewController topController = mBrowseStack.getCurrentController();
        if (topController == null) {
            Log.e(TAG, "topController should not be null in getNextEntryType!!");
            return BrowseEntryType.LINK;
        }

        if (topController.hasChild(item)) {
            // If the item is a child of the top controller, treat this as a regular browse action
            BrowseEntryType currentType = mBrowseStack.getCurrentEntryType();
            if (currentType == null) {
                Log.e(TAG, "mBrowseStack.getCurrentEntryType returned null !?!");
                return BrowseEntryType.LINK;
            }
            return currentType.getNextEntryBrowseType();
        }

        return BrowseEntryType.LINK;
    }

    /** Fetches the given media item and displays it. Can be called before being connected. */
    public void navigateTo(@Nullable String mediaId) {
        mPendingMediaId = mediaId;
        if (TextUtils.isEmpty(mPendingMediaId)) {
            return;
        }

        BrowsingState state = mMediaItemsRepository.getBrowsingState().getValue();
        if ((state != null) && state.mConnectionStatus == CONNECTED) {
            Log.i(TAG, "Fetching: " + mediaId);

            mMediaItemsRepository.getItem(mPendingMediaId,
                    new MediaBrowserCompat.ItemCallback() {
                        @Override
                        public void onItemLoaded(MediaBrowserCompat.MediaItem item) {
                            String itemId = (item != null) ? item.getMediaId() : null;
                            if (Objects.equals(mPendingMediaId, itemId)) {
                                mPendingMediaId = null;
                                MediaItemMetadata mim = new MediaItemMetadata(item);
                                navigateTo(mim);
                            } else {
                                Log.e(TAG, "ID mismatch. requested: [" + mPendingMediaId
                                        + "], received: [" + itemId + "]");
                            }
                        }

                        @Override
                        public void onError(@NonNull String itemId) {
                            Log.e(TAG, "Failed to fetch item: " + itemId);
                        }
                    });
        } else {
            Log.i(TAG, "Waiting to fetch: " + mediaId);
        }
    }

    private void navigateTo(@NonNull MediaItemMetadata item) {
        if (Objects.equals(item, mBrowseStack.getCurrentMediaItem())) {
            Log.i(TAG, "navigateInto item already shown");
            return;
        }

        // Hide the current node (eg: parent)
        showCurrentNode(false);

        // Make item the current node
        mBrowseStack.pushEntry(getNextEntryType(item), item, createControllerForItem(item));

        // Show the current node (item)
        showCurrentNode(true);

        updateAppBar();
    }

    @NonNull
    private BrowseViewController createControllerForItem(@NonNull MediaItemMetadata item) {
        BrowseViewController controller =
                BrowseViewController.newBrowseController(mBrowseCallbacks, mBrowseArea,
                        item, mMediaItemsRepository, mRootBrowsableHint, mRootPlayableHint);
        adjustBoundaries(controller);
        return controller;
    }

    private void adjustBoundaries(@NonNull BrowseViewController controller) {
        if (mCarUiInsets != null) {
            controller.onCarUiInsetsChanged(mCarUiInsets);
        }
        controller.onPlaybackControlsChanged(mPlaybackControlsVisible);
    }

    private void showCurrentNode(boolean show) {
        BrowseStack.BrowseEntry entry = mBrowseStack.peek();
        if (entry == null) {
            Log.e(TAG, "Can't show a null entry!");
            return;
        }

        BrowseViewController controller = entry.getController();
        if (controller == null && show) {
            // Controller was previously destroyed by a media source or UI config change, recreate.
            controller = recreateController(entry);
            if (controller != null) {
                adjustBoundaries(controller);
                entry.setRecreatedController(controller);
            }
        }

        if (controller != null) {
            showHideContentAnimated(show, controller.getContent(), mViewAnimEndListener);
            sendNodeChangeAnalytics(show, entry);
        }
    }

    private void sendNodeChangeAnalytics(boolean show,
            @NonNull BrowseStack.BrowseEntry browseEntry) {

        BrowseViewController controller = browseEntry.getController();

        if (controller != null) {
            String currentId = null;
            if (browseEntry.mItem != null) {
                currentId = browseEntry.mItem.getId();
            }
            mMediaItemsRepository.getAnalyticsManager().sendBrowseChangeEvent(
                    browseEntryTypeToAnalytic(browseEntry.mType),
                    show ? AnalyticsEvent.SHOW : AnalyticsEvent.HIDE, currentId);
            controller.onShow(show);
        }
    }

    // If the current node has a CarUiRecyclerView and it's in rotary mode, restore focus in it.
    // Should remain private and definitely NOT be called from MediaActivity#changeModeInternal
    // as the controller isn't ready to show the browse data of the new media source (it hasn't
    // connected to it (b/217159531).
    private void restoreFocusInCurrentNode() {
        BrowseViewController controller = mBrowseStack.getCurrentController();
        if (controller == null) {
            return;
        }
        CarUiRecyclerView carUiRecyclerView =
                controller.getContent().findViewById(R.id.browse_list);
        if (carUiRecyclerView instanceof LazyLayoutView
                && !carUiRecyclerView.getView().hasFocus()
                && !carUiRecyclerView.getView().isInTouchMode()) {
            LazyLayoutView lazyLayoutView = (LazyLayoutView) carUiRecyclerView;
            com.android.car.ui.utils.ViewUtils.initFocus(lazyLayoutView);
        }
    }

    private void showHideContentAnimated(boolean show, @NonNull View content,
            @Nullable ViewAnimEndListener listener) {
        CarUiRecyclerView carUiRecyclerView = content.findViewById(R.id.browse_list);
        if (carUiRecyclerView instanceof LazyLayoutView
                && !carUiRecyclerView.getView().isInTouchMode()) {
            // If a CarUiRecyclerView is about to hide and it has focus, park the focus on the
            // FocusParkingView before hiding the CarUiRecyclerView. Otherwise hiding the focused
            // view will cause the Android framework to move focus to another view, causing visual
            // jank.
            if (!show && carUiRecyclerView.getView().hasFocus()) {
                mFpv.performAccessibilityAction(ACTION_FOCUS, null);
            }
            // If a new CarUiRecyclerView is about to show and there is no view focused or the
            // FocusParkingView is focused, restore focus in the new CarUiRecyclerView.
            if (show) {
                View focusedView = carUiRecyclerView.getView().getRootView().findFocus();
                if (focusedView == null || focusedView instanceof FocusParkingView) {
                    LazyLayoutView lazyLayoutView = (LazyLayoutView) carUiRecyclerView;
                    com.android.car.ui.utils.ViewUtils.initFocus(lazyLayoutView);
                }
            }
        }

        showHideViewAnimated(show, content, mFadeDuration, listener);
    }

    @BrowseChangeEvent.BrowseMode
    private int browseEntryTypeToAnalytic(BrowseStack.BrowseEntryType type) {
        switch(type){
            case TREE_TAB:
                return BrowseChangeEvent.TREE_TAB;
            case TREE_ROOT:
                return BrowseChangeEvent.TREE_ROOT;
            case TREE_BROWSE:
                return BrowseChangeEvent.TREE_BROWSE;
            case SEARCH_RESULTS:
                return BrowseChangeEvent.SEARCH_RESULTS;
            case SEARCH_BROWSE:
                return BrowseChangeEvent.SEARCH_BROWSE;
            case LINK:
                return BrowseChangeEvent.LINK;
            case LINK_BROWSE:
                return BrowseChangeEvent.LINK_BROWSE;
        }

        return BrowseChangeEvent.UNKNOWN;
    }

    private void showSearchResults() {
        // Remove previous search entries from the stack (if any)
        hideAndDestroyStackEntries(mBrowseStack.removeSearchEntries());

        // Hide the current node
        showCurrentNode(false);

        // Push a new search controller
        BrowseViewController controller = BrowseViewController.newSearchResultsController(
                mBrowseCallbacks, mBrowseArea, mMediaItemsRepository);
        adjustBoundaries(controller);
        if (mToolbarSearchResultsView != null) {
            controller.shareBrowseAdapterWith(mToolbarSearchResultsView);
        }
        mBrowseStack.pushSearchResults(controller);

        // Show the search controller
        showCurrentNode(true);

        updateAppBar();
        mAppBarController.setSearchQuery(mViewModel.getSearchQuery());

    }

    @Override
    public void onCarUiInsetsChanged(@NonNull Insets insets) {
        mCarUiInsets = insets;
        for (BrowseStack.BrowseEntry entry : mBrowseStack.getEntries()) {
            if (entry.getController() != null) {
                entry.getController().onCarUiInsetsChanged(mCarUiInsets);
            }
        }
    }

    void onPlaybackControlsChanged(boolean visible) {
        mPlaybackControlsVisible = visible;
        for (BrowseStack.BrowseEntry entry : mBrowseStack.getEntries()) {
            if (entry.getController() != null) {
                entry.getController().onPlaybackControlsChanged(mPlaybackControlsVisible);
            }
        }
    }

    NowPlayingController getNowPlayingController() {
        return mNowPlayingController;
    }

    private void hideKeyboard() {
        InputMethodManager in =
                (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        in.hideSoftInputFromWindow(mContent.getWindowToken(), 0);
    }

    private void hideAndDestroyStackEntry(@NonNull BrowseStack.BrowseEntry entry) {
        BrowseViewController controller = entry.getController();
        if (controller == null) {
            Log.e(TAG, "Controller already destroyed for: " + entry.mItem);
            return;
        }
        if (controller.getContent().getVisibility() == View.VISIBLE) {
            View view = controller.getContent();
            mEntriesToDestroy.put(view, entry);
            showHideContentAnimated(false, view, mViewAnimEndListener);
            sendNodeChangeAnalytics(false, entry);
        } else {
            entry.destroyController();
        }

        if (mToolbarSearchResultsView != null && entry.mType == BrowseEntryType.SEARCH_RESULTS) {
            mToolbarSearchResultsView.setAdapter(null);
        }
    }

    /**
     * Destroys the given stack entries (after their view is hidden).
     */
    private void hideAndDestroyStackEntries(List<BrowseStack.BrowseEntry> entries) {
        for (BrowseStack.BrowseEntry entry : entries) {
            hideAndDestroyStackEntry(entry);
        }
    }

    /**
     * Updates the tabs displayed on the app bar, based on the top level items on the browse tree.
     * If there is at least one browsable item, we show the browse content of that node. If there
     * are only playable items, then we show those items. If there are not items at all, we show the
     * empty message. If we receive null, we show the error message.
     *
     * @param items top level items, null if the items are still being loaded, or empty list if
     *              items couldn't be loaded.
     */
    private void updateTabs(@Nullable List<MediaItemMetadata> items) {
        if (Objects.equals(mTopItems, items)) {
            // When coming back to the app, the live data sends an update even if the list hasn't
            // changed. Updating the tabs then recreates the browse view, which produces jank
            // (b/131830876), and also resets the navigation to the top of the first tab...
            return;
        }
        mTopItems = items;
        if (mTopItems == null || mTopItems.isEmpty()) {
            mAppBarController.setItems(null);
            mAppBarController.setActiveItem(null);
            if (items != null) {
                // Only do this when not loading the tabs or we loose the saved one.
                hideAndDestroyStackEntries(mBrowseStack.removeTreeEntriesExceptRoot());
            }
            updateAppBar();
            return;
        }

        MediaItemMetadata oldTab = getSelectedTab();
        MediaItemMetadata newTab = items.contains(oldTab) ? oldTab : items.get(0);

        try {
            mAcceptTabSelection = false;
            mAppBarController.setItems(mTopItems.size() == 1 ? null : mTopItems);
            mAppBarController.setActiveItem(newTab);

            if (oldTab != newTab) {
                // Tabs belong to the browse stack.
                hideAndDestroyStackEntries(mBrowseStack.removeTreeEntriesExceptRoot());
                mBrowseStack.insertRootTab(newTab, createControllerForItem(newTab));
            }

            if (mBrowseStack.size() <= 2) {
                // Needed when coming back to an app after a config change or from another app,
                // or when the tab actually changes.
                showCurrentNode(true);
            }
        }  finally {
            mAcceptTabSelection = true;
        }
        updateAppBar();
    }

    private CharSequence getAppBarTitle() {
        final CharSequence title;
        if (isStacked()) {
            // If not at top level, show the current item as title
            MediaItemMetadata item = mBrowseStack.getCurrentMediaItem();
            title = item != null ? item.getTitle() : "";
        } else if (mTopItems == null) {
            // If still loading the tabs, force to show an empty bar.
            title = "";
        } else if (mTopItems.size() == 1) {
            // If we finished loading tabs and there is only one, use that as title.
            title = mTopItems.get(0).getTitle();
        } else {
            // Otherwise (no tabs or more than 1 tabs), show the current media source title.
            MediaSource mediaSource = mViewModel.getMediaSourceValue();
            title = getAppBarDefaultTitle(mediaSource);
        }

        return title;
    }

    /**
     * Update elements of the appbar that change depending on where we are in the browse.
     */
    private void updateAppBar() {
        boolean isSearchMode = mBrowseStack.isShowingSearchResults();
        boolean isStacked = isStacked();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "App bar is in stacked state: " + isStacked);
        }

        mAppBarController.setSearchMode(isSearchMode ? SearchMode.SEARCH : SearchMode.DISABLED);
        mAppBarController.setNavButtonMode(isStacked ? NavButtonMode.BACK : NavButtonMode.DISABLED);
        mAppBarController.setTitle(getAppBarTitle());
        mAppBarController.showSearchIfSupported(!isSearchMode);
    }

    private void onRootMediaItemsUpdate(FutureData<List<MediaItemMetadata>> data) {
        if (data.isLoading()) {
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "Loading browse tree...");
            }
            mBrowseTreeHasChildrenList = false;
            updateTabs(null);
            return;
        }

        List<MediaItemMetadata> items =
                MediaBrowserViewModelImpl.filterItems(/*forRoot*/ true, data.getData());

        boolean browseTreeHasChildrenList = items != null;
        if (Log.isLoggable(TAG, Log.INFO)) {
            Log.i(TAG, "Browse tree loaded, status (has children or not) changed: "
                    + mBrowseTreeHasChildrenList + " -> " + browseTreeHasChildrenList);
        }
        mBrowseTreeHasChildrenList = browseTreeHasChildrenList;
        mCallbacks.onRootLoaded();
        updateTabs(items != null ? items : new ArrayList<>());
    }
}
