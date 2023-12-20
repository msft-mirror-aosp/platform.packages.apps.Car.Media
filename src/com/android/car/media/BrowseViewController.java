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

import static android.car.media.CarMediaManager.MEDIA_SOURCE_MODE_BROWSE;
import static android.car.media.CarMediaManager.MEDIA_SOURCE_MODE_PLAYBACK;

import static androidx.car.app.mediaextensions.analytics.event.AnalyticsEvent.VIEW_ACTION_HIDE;
import static androidx.car.app.mediaextensions.analytics.event.AnalyticsEvent.VIEW_ACTION_SHOW;
import static androidx.car.app.mediaextensions.analytics.event.AnalyticsEvent.VIEW_COMPONENT_BROWSE_LIST;
import static androidx.recyclerview.widget.RecyclerView.NO_POSITION;

import static com.android.car.apps.common.util.ViewUtils.removeFromParent;
import static com.android.car.media.browse.BrowseItemViewType.GRID_ITEM;
import static com.android.car.media.browse.BrowseItemViewType.ICON_GRID_ITEM;
import static com.android.car.media.browse.BrowseItemViewType.ICON_LIST_ITEM;
import static com.android.car.media.browse.BrowseItemViewType.LIST_ITEM;
import static com.android.car.media.common.MediaConstants.BROWSE_CUSTOM_ACTIONS_MEDIA_ITEM_ID;
import static com.android.car.media.common.MediaConstants.KEY_HINT_HOST_PACKAGE_NAME;
import static com.android.car.media.common.MediaConstants.KEY_HINT_VIEW_HEIGHT_PIXELS;
import static com.android.car.media.common.MediaConstants.KEY_HINT_VIEW_MAX_CATEGORY_GRID_ITEMS_COUNT_PER_ROW;
import static com.android.car.media.common.MediaConstants.KEY_HINT_VIEW_MAX_CATEGORY_LIST_ITEMS_COUNT_PER_ROW;
import static com.android.car.media.common.MediaConstants.KEY_HINT_VIEW_MAX_GRID_ITEMS_COUNT_PER_ROW;
import static com.android.car.media.common.MediaConstants.KEY_HINT_VIEW_MAX_ITEMS_WHILE_RESTRICTED;
import static com.android.car.media.common.MediaConstants.KEY_HINT_VIEW_MAX_LIST_ITEMS_COUNT_PER_ROW;
import static com.android.car.media.common.MediaConstants.KEY_HINT_VIEW_WIDTH_PIXELS;
import static com.android.car.ui.recyclerview.CarUiRecyclerView.SCROLL_STATE_DRAGGING;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaBrowserCompat.ItemCallback;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.car.app.mediaextensions.analytics.event.AnalyticsEvent;
import androidx.core.util.Pair;
import androidx.core.util.Preconditions;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.apps.common.imaging.ImageBinder;
import com.android.car.apps.common.util.FutureData;
import com.android.car.apps.common.util.LiveDataFunctions;
import com.android.car.apps.common.util.ViewUtils;
import com.android.car.media.browse.BrowseAdapter;
import com.android.car.media.browse.BrowseAdapterUtils;
import com.android.car.media.browse.BrowseMiniMediaItemView;
import com.android.car.media.browse.BrowseViewHolder;
import com.android.car.media.browse.LimitedBrowseAdapter;
import com.android.car.media.browse.actionbar.ActionsHeader;
import com.android.car.media.common.CustomBrowseAction;
import com.android.car.media.common.MediaConstants;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.browse.MediaBrowserViewModelImpl;
import com.android.car.media.common.browse.MediaItemsRepository;
import com.android.car.media.common.browse.MediaItemsRepository.MediaItemsLiveData;
import com.android.car.media.common.playback.PlaybackProgress;
import com.android.car.media.common.playback.PlaybackViewModel;
import com.android.car.media.common.source.MediaBrowserConnector.BrowsingState;
import com.android.car.media.common.source.MediaSource;
import com.android.car.ui.AlertDialogBuilder;
import com.android.car.ui.FocusArea;
import com.android.car.ui.baselayout.Insets;
import com.android.car.ui.recyclerview.CarUiContentListItem;
import com.android.car.ui.recyclerview.CarUiLayoutStyle;
import com.android.car.ui.recyclerview.CarUiListItemAdapter;
import com.android.car.ui.recyclerview.CarUiRecyclerView;
import com.android.car.uxr.CarUxRestrictionsAppConfig;
import com.android.car.uxr.LifeCycleObserverUxrContentLimiter;
import com.android.car.uxr.UxrContentLimiterImpl;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A view controller that displays the media item children of a {@link MediaItemMetadata}.
 * The controller manages a recycler view where the items can be displayed as a list or a grid, as
 * well as an error icon and a message used to indicate loading and errors.
 * The content view is initialized with 0 alpha and needs to be animated or set to to full opacity
 * to become visible.
 */
@OptIn(markerClass = androidx.car.app.annotations2.ExperimentalCarApi.class)
public class BrowseViewController {
    private static final String TAG = "BrowseViewController";

    private final Callbacks mCallbacks;
    private final ViewGroup mContainer;
    private final FocusArea mFocusArea;
    private final MediaItemMetadata mParentItem;
    private List<CustomBrowseAction> mParentActions;
    private final MediaItemsLiveData mMediaItems;
    private final boolean mDisplayMediaItems;
    private final LifeCycleObserverUxrContentLimiter mUxrContentLimiter;
    private final View mContent;
    private final CarUiRecyclerView mBrowseList;
    private final ImageView mErrorIcon;
    private final TextView mMessage;
    private final LimitedBrowseAdapter mLimitedBrowseAdapter;
    private BrowseMiniMediaItemView mEmptyListPlaybackBar;

    private final int mFadeDuration;
    private final int mLoadingIndicatorDelay;

    private final boolean mSetFocusAreaHighlightBottom;

    private final Handler mHandler = new Handler();

    private final MediaActivity.ViewModel mViewModel;

    private final PlaybackViewModel mPlaybackViewModel;
    private final PlaybackViewModel mPlaybackViewModelBrowseSource;
    private MediaItemsRepository mMediaRepo;
    private Map<String, CustomBrowseAction> mGlobalActions = new HashMap<>();
    private ActionsHeader mActionBar;
    /** See {@link #onShow}. */
    private boolean mIsShown;
    List<String> mPrevVisible = new ArrayList<>();



    private final BrowseAdapter.Observer mBrowseAdapterObserver = new BrowseAdapter.Observer() {
        @Override
        protected void onPlayableItemClicked(@NonNull MediaItemMetadata item) {
            if (item.getId() != null) {
                mMediaRepo.getAnalyticsManager().sendMediaClickedEvent(item.getId(),
                        VIEW_COMPONENT_BROWSE_LIST);
            }
            mCallbacks.onPlayableItemClicked(item);
        }

        @Override
        protected void onBrowsableItemClicked(@NonNull MediaItemMetadata item) {
            if (item.getId() != null) {
                mMediaRepo.getAnalyticsManager().sendMediaClickedEvent(item.getId(),
                        VIEW_COMPONENT_BROWSE_LIST);
            }
            mCallbacks.goToMediaItem(item);
        }

        @Override
        protected void onBrowseCustomActionClicked(
                @NonNull CustomBrowseAction customBrowseAction, String mediaId) {
            sendBrowseCustomAction(customBrowseAction, mediaId);
        }

        @Override
        protected void onBrowseCustomActionOverflowClicked(
                @NonNull List<CustomBrowseAction> overflowActions, String mediaId) {
            sendBrowseCustomActionEvent(mediaId, overflowActions, true);
            showOverflowActions(overflowActions, mediaId);
        }
    };

    /**
     * Called when BVC is shown/hidden by {@link MediaActivityController} (for its associated item)
     * within the browse view. The given isShown does NOT reflect what is happening to the browse
     * view itself (like being moved or faded away when going to the playback screen).
     */
    public void onShow(boolean isShown, @NonNull BrowseStack.BrowseEntryType type) {
        mIsShown = isShown;

        // Send analytics
        String parentId = mParentItem == null ? "" : mParentItem.getId();

        mMediaRepo.getAnalyticsManager().sendBrowseChangeEvent(
                type.toAnalyticBrowseMode(), isShown ? VIEW_ACTION_SHOW : VIEW_ACTION_HIDE,
                parentId);

        int firsPos = mLimitedBrowseAdapter.findFirstVisibleItemIndex();
        int lastPov = mLimitedBrowseAdapter.findLastVisibleItemIndex(false);
        if (mMediaItems.getValue() != null && mMediaItems.getValue().getData() != null
                && firsPos != NO_POSITION && lastPov != NO_POSITION) {
            List<String> itemsSublist = mMediaItems.getValue().getData()
                    .subList(firsPos, lastPov + 1)
                    .stream()
                    .map(MediaItemMetadata::getId)
                    .collect(Collectors.toCollection(ArrayList::new));

            mMediaRepo.getAnalyticsManager().sendVisibleItemsEvents(
                    parentId, VIEW_COMPONENT_BROWSE_LIST,
                    isShown ? AnalyticsEvent.VIEW_ACTION_SHOW : AnalyticsEvent.VIEW_ACTION_HIDE,
                    AnalyticsEvent.VIEW_ACTION_MODE_NONE, itemsSublist);
        }
    }

    /** Callback from browse service for custom actions */
    public static class CustomActionCallback extends MediaBrowserCompat.CustomActionCallback {

        WeakReference<BrowseViewController> mBrowseViewControllerWeakReference;

        public CustomActionCallback(BrowseViewController browseViewController) {
            this.mBrowseViewControllerWeakReference = new WeakReference<>(browseViewController);
        }

        @Override
        public void onProgressUpdate(String action, Bundle extras, Bundle resultData) {
            BrowseViewController bvc = mBrowseViewControllerWeakReference.get();
            if (bvc != null) {
                bvc.handleBrowseCustomActionResult(action, extras, resultData);
            }
        }

        @Override
        public void onResult(String action, Bundle extras, Bundle resultData) {
            BrowseViewController bvc = mBrowseViewControllerWeakReference.get();
            if (bvc != null) {
                bvc.handleBrowseCustomActionResult(action, extras, resultData);
            }
        }

        @Override
        public void onError(String action, Bundle extras, Bundle resultData) {
            Log.e(TAG, "CustomActionCallback onError: " + action);
            BrowseViewController bvc = mBrowseViewControllerWeakReference.get();
            if (bvc != null) {
                if (resultData.containsKey(
                        MediaConstants.BROWSE_CUSTOM_ACTIONS_EXTRA_RESULT_MESSAGE)) {
                    String text =
                            resultData.getString(
                                    MediaConstants.BROWSE_CUSTOM_ACTIONS_EXTRA_RESULT_MESSAGE);
                    Toast.makeText(bvc.mContent.getContext(), text, Toast.LENGTH_SHORT).show();
                }
            }
        }
    };

    /**
     * The bottom padding of the FocusArea highlight.
     */
    private int mFocusAreaHighlightBottomPadding;

    /**
     * Callbacks (implemented by the host)
     */
    public interface Callbacks {
        /**
         * Method invoked when the user clicks on a playable item
         *
         * @param item item to be played.
         */
        void onPlayableItemClicked(@NonNull MediaItemMetadata item);

        /** Displays the given item. It may not be a child of the current node. */
        void goToMediaItem(@NonNull MediaItemMetadata item);

        /** Invoked when user clicks on the mini playback bar in an empty browse
         *
         * This item exists in the case where we have an empty list and haven't seen a playable item
         * but we have a queue or metadata.
         * This allows the user to start the queue in a media continuity situation.
         *
         */
        void onBrowseEmptyListPlayItemClicked();

        /**
         * Opens Playback view without starting new content.
         */
        void openPlaybackView();

        /** Returns whether the entire browse view is visible. */
        boolean isBrowseViewVisible();

        /** Invoked when child nodes have been removed from this controller. */
        void onChildrenNodesRemoved(@NonNull BrowseViewController controller,
                @NonNull Collection<MediaItemMetadata> removedNodes);

        FragmentActivity getActivity();

        /** Hides the keyboard if it's visible. */
        void hideKeyboard();
    }

    private FragmentActivity getActivity() {
        return mCallbacks.getActivity();
    }

    /**
     * Creates a controller to display the children of the given parent {@link MediaItemMetadata}.
     * This parent node can have been obtained from the browse tree, or from browsing the search
     * results.
     */
    static BrowseViewController newBrowseController(
            Callbacks callbacks,
            ViewGroup container,
            @NonNull MediaItemMetadata parentItem,
            MediaItemsRepository mediaRepo,
            int rootBrowsableHint,
            int rootPlayableHint) {
        return new BrowseViewController(callbacks, container, parentItem, rootBrowsableHint,
                rootPlayableHint, mediaRepo, true);
    }

    /** Creates a controller to display the top results of a search query (in a list). */
    static BrowseViewController newSearchResultsController(
            Callbacks callbacks, ViewGroup container, MediaItemsRepository mediaRepo) {
        return new BrowseViewController(callbacks, container, null, 0, 0, mediaRepo, true);
    }

    /**
     * Creates a controller to "display" the children of the root: the children are actually hidden
     * since they are shown as tabs, and the controller is only used to display loading and error
     * messages.
     */
    static BrowseViewController newRootController(
            MediaItemMetadata parentItem,
            Callbacks callbacks,
            ViewGroup container,
            MediaItemsRepository mediaRepo) {
        return new BrowseViewController(callbacks, container, parentItem, 0, 0, mediaRepo, false);
    }

    private static Bundle createItemSubscriptionOptions(View myView, CarUiRecyclerView browseList) {
        Context ctx = myView.getContext();
        Bundle options = new Bundle();
        options.putString(KEY_HINT_HOST_PACKAGE_NAME, ctx.getPackageName());
        options.putInt(KEY_HINT_VIEW_WIDTH_PIXELS, myView.getWidth());
        options.putInt(KEY_HINT_VIEW_HEIGHT_PIXELS, myView.getHeight());
        options.putInt(KEY_HINT_VIEW_MAX_ITEMS_WHILE_RESTRICTED, getMaxItemsWhileDriving(ctx));

        CarUiLayoutStyle style = browseList.getLayoutStyle();
        Preconditions.checkNotNull(style, "browseList.getLayoutStyle is null!");
        int maxSpans = style.getSpanCount();
        int maxListItems = maxSpans / LIST_ITEM.getSpanSize(maxSpans);
        int maxCatListItems = maxSpans / ICON_LIST_ITEM.getSpanSize(maxSpans);
        int maxGridItems = maxSpans / GRID_ITEM.getSpanSize(maxSpans);
        int maxCatGridItems = maxSpans / ICON_GRID_ITEM.getSpanSize(maxSpans);
        options.putInt(KEY_HINT_VIEW_MAX_LIST_ITEMS_COUNT_PER_ROW, maxListItems);
        options.putInt(KEY_HINT_VIEW_MAX_CATEGORY_LIST_ITEMS_COUNT_PER_ROW, maxCatListItems);
        options.putInt(KEY_HINT_VIEW_MAX_GRID_ITEMS_COUNT_PER_ROW, maxGridItems);
        options.putInt(KEY_HINT_VIEW_MAX_CATEGORY_GRID_ITEMS_COUNT_PER_ROW, maxCatGridItems);

        return options;
    }

    private static int getMaxItemsWhileDriving(Context context) {
        Integer maxItems = CarUxRestrictionsAppConfig.getContentLimit(context,
                R.xml.uxr_config, R.id.browse_list_uxr_config);
        Preconditions.checkNotNull(maxItems, "Misconfigured list limits.");
        return maxItems;
    }

    /**
     * {@link RecyclerView.AdapterDataObserver} For listening to payload changes with
     * {@link BrowseAdapter#updateItemMetaData(MediaItemMetadata)} which calls
     * {@link androidx.recyclerview.widget.ListAdapter#notifyItemChanged(int, Object)} which then
     * call this observer.  Without this being set, it would go to default implementation that will
     * ignore the payload and do a full bind instead of a partial bind.
     */
    private static class BrowseAdapterObservable extends RecyclerView.AdapterDataObserver {
        BrowseAdapter mBrowseAdapter;
        CarUiRecyclerView mRecyclerView;

        BrowseAdapterObservable(BrowseAdapter browseAdapter,
                CarUiRecyclerView recyclerView) {
            mBrowseAdapter = browseAdapter;
            mRecyclerView = recyclerView;
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount,
                @Nullable Object payload) {
            if (positionStart >= 0 && itemCount >= 0) {
                if (mRecyclerView != null && mBrowseAdapter != null) {
                    BrowseViewHolder holder =
                            (BrowseViewHolder) mRecyclerView.findViewHolderForAdapterPosition(
                                    positionStart);
                    if (holder != null) {
                        mBrowseAdapter.onBindViewHolder(holder, positionStart,
                                Collections.singletonList(payload));
                    }
                }
            }
        }
    }

    private abstract static class BrowseActionCallback extends ItemCallback{
        @Override
        public abstract void onItemLoaded(MediaItem item);

        @Override
        public void onError(@NonNull String itemId) {
            super.onError(itemId);
            Log.e(TAG, "BrowseActionCallback#onError -> " + itemId);
        }
    }

    private BrowseViewController(
            Callbacks callbacks,
            ViewGroup container,
            @Nullable MediaItemMetadata parentItem,
            int rootBrowsableHint,
            int rootPlayableHint,
            MediaItemsRepository mediaRepo,
            boolean displayMediaItems) {
        mCallbacks = callbacks;
        mContainer = container;
        mParentItem = parentItem;
        mDisplayMediaItems = displayMediaItems;
        mMediaRepo = mediaRepo;

        FragmentActivity activity = callbacks.getActivity();
        mViewModel = new ViewModelProvider(activity).get(MediaActivity.ViewModel.class);

        LayoutInflater inflater = LayoutInflater.from(container.getContext());
        mContent = inflater.inflate(R.layout.browse_node, container, false);
        mContent.setAlpha(0f);
        container.addView(mContent);

        int maxActions = mContent.getContext().getResources()
                .getInteger(com.android.car.media.common.R.integer.max_custom_actions);
        initCustomActionsHeader(parentItem, maxActions);

        Resources res = mContent.getContext().getResources();
        mLoadingIndicatorDelay = res.getInteger(R.integer.progress_indicator_delay);
        mSetFocusAreaHighlightBottom = res.getBoolean(
                R.bool.set_browse_list_focus_area_highlight_above_minimized_control_bar);

        mFocusArea = mContent.findViewById(R.id.focus_area);
        mBrowseList = mContent.findViewById(R.id.browse_list);
        mErrorIcon = mContent.findViewById(R.id.error_icon);
        mMessage = mContent.findViewById(R.id.error_message);
        mFadeDuration = mContent.getContext().getResources().getInteger(
                R.integer.new_album_art_fade_in_duration);

        mPlaybackViewModel = mViewModel.getPlaybackViewModel(MEDIA_SOURCE_MODE_PLAYBACK);
        mPlaybackViewModelBrowseSource = mViewModel.getPlaybackViewModel(MEDIA_SOURCE_MODE_BROWSE);

        LiveDataFunctions.pair(mPlaybackViewModel.getProgress(), mPlaybackViewModel.getMetadata())
                .observe(activity, this::handleProgressUpdate);

        LiveDataFunctions.pair(mPlaybackViewModel.getMediaSource(),
                        mPlaybackViewModelBrowseSource.getMediaSource())
                .observe(activity, this::handleSourceUpdates);

        BrowseAdapter browseAdapter = new BrowseAdapter(mBrowseList.getContext());
        browseAdapter.registerAdapterDataObserver(
                new BrowseAdapterObservable(browseAdapter, mBrowseList));
        mLimitedBrowseAdapter = new LimitedBrowseAdapter(mBrowseList, browseAdapter,
                mBrowseAdapterObserver);
        mBrowseList.setHasFixedSize(true);
        mBrowseList.setAdapter(mLimitedBrowseAdapter);
        mBrowseList.addOnScrollListener(new CarUiRecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(CarUiRecyclerView recyclerView, int dx, int dy) {
                // dx and dy are 0 when items in RV change or layout is requested. We should
                // use this to trigger querying what is visible.
                maybeSendVisibleItemsIncremental(dx != 0 || dy != 0);
            }

            @Override
            public void onScrollStateChanged(CarUiRecyclerView recyclerView, int newState) {
                if (newState == SCROLL_STATE_DRAGGING) {
                    if (res.getBoolean(R.bool.hide_search_keyboard_on_scroll_state_dragging)) {
                        mCallbacks.hideKeyboard();
                    }
                }
            }
        });

        ViewCompat.setWindowInsetsAnimationCallback(mBrowseList.getView().getRootView(),
                new WindowInsetsAnimationCompat.Callback(
                        WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_STOP) {
                    @NonNull
                    @Override
                    public WindowInsetsCompat onProgress(
                            @NonNull WindowInsetsCompat windowInsetsCompat,
                            @NonNull List<WindowInsetsAnimationCompat> list) {
                        return windowInsetsCompat;
                    }

                    @Override
                    public void onEnd(@NonNull WindowInsetsAnimationCompat animation) {
                        super.onEnd(animation);
                        maybeSendVisibleItemsIncremental(false);
                    }
                });

        mUxrContentLimiter = new LifeCycleObserverUxrContentLimiter(
                new UxrContentLimiterImpl(activity, R.xml.uxr_config));
        mUxrContentLimiter.setAdapter(mLimitedBrowseAdapter);
        activity.getLifecycle().addObserver(mUxrContentLimiter);

        mMediaRepo.getCustomBrowseActions().observe(activity, actions -> {
            mGlobalActions = actions;
            browseAdapter.setGlobalCustomActions(actions);
            configureCustomActionsHeader(actions, maxActions);
        });

        browseAdapter.setRootBrowsableViewType(rootBrowsableHint);
        browseAdapter.setRootPlayableViewType(rootPlayableHint);
        browseAdapter.setGlobalCustomActions(mGlobalActions);

        Bundle options = createItemSubscriptionOptions(mContainer, mBrowseList);
        if (parentItem != null) {
            mMediaItems = mediaRepo.getMediaChildren(parentItem.getId(), options);
        } else {
            mMediaItems = mediaRepo.getSearchMediaItems();
        }
        mMediaItems.observe(activity, mItemsObserver);

    }

    private void maybeSendVisibleItemsIncremental(boolean fromScroll) {
        if (mIsShown && mCallbacks.isBrowseViewVisible() && (mMediaItems.getValue() != null)
                && mMediaItems.getValue().getData() != null) {
            int currFirst = mLimitedBrowseAdapter.findFirstVisibleItemIndex();
            int currLast = mLimitedBrowseAdapter.findLastVisibleItemIndex(true);
            mPrevVisible = AnalyticsHelper.sendVisibleItemsInc(VIEW_COMPONENT_BROWSE_LIST,
                    mMediaRepo, mParentItem, mPrevVisible, mMediaItems.getValue().getData(),
                    currFirst, currLast, fromScroll);
        }
    }


    /**
     * Returns whether the children of the parentItem given to this controller have been loaded and
     * the given item is one of them.
     */
    boolean hasChild(MediaItemMetadata item) {
        List<MediaItemMetadata> children = FutureData.getData(mMediaItems.getValue());
        return (children != null) && (children.contains(item));
    }

    private void initCustomActionsHeader(MediaItemMetadata parentItem, int maxActions) {
        if (parentItem == null || maxActions <= 0) {
            return;
        }
        mActionBar = mContent.findViewById(R.id.toolbar_container);
        mActionBar.setActionClickedListener(
                action -> sendBrowseCustomAction(action, parentItem.getId()));
        mActionBar.setOnOverflowListener(actions -> {
            sendBrowseCustomActionEvent(parentItem.getId(), actions, true);
            showOverflowActions(actions, parentItem.getId());
        });
    }

    private void sendBrowseCustomActionEvent(String itemId, List<CustomBrowseAction> actions,
            boolean isShow) {
        mMediaRepo.getAnalyticsManager()
                .sendVisibleItemsEvents(itemId,
                        AnalyticsEvent.VIEW_COMPONENT_BROWSE_ACTION_OVERFLOW,
                        isShow ? AnalyticsEvent.VIEW_ACTION_SHOW : AnalyticsEvent.VIEW_ACTION_HIDE,
                        AnalyticsEvent.VIEW_ACTION_MODE_NONE, actions.stream()
                                .map(CustomBrowseAction::getId)
                                .collect(Collectors.toList()));
    }

    private void configureCustomActionsHeader(
            @NonNull Map<String, CustomBrowseAction> globalActions, int maxActions) {
        if (mActionBar == null || maxActions <= 0) return;
        mParentActions =
                BrowseAdapterUtils.buildBrowseCustomActions(
                        mContent.getContext(), mParentItem, globalActions);
        if (mParentActions == null || mParentActions.isEmpty()) return;
        mActionBar.setVisibility(true);
        mActionBar.setActions(mParentActions);
    }

    private void sendBrowseCustomAction(CustomBrowseAction customBrowseAction, String mediaItemId) {
        final BrowsingState browsingState = mMediaRepo.getBrowsingState().getValue();
        if (browsingState != null) {
            final MediaBrowserCompat mediaBrowserCompat = browsingState.mBrowser;
            Bundle extras = new Bundle();
            //We need to pass this to browse service in order for them to properly handle action
            extras.putString(BROWSE_CUSTOM_ACTIONS_MEDIA_ITEM_ID, mediaItemId);
            mediaBrowserCompat.sendCustomAction(
                    customBrowseAction.getId(), extras, new CustomActionCallback(this));
        }
    }

    private void showOverflowActions(List<CustomBrowseAction> overflowActions, String mediaId) {
        final Size mMaxArtSize =
                MediaAppConfig.getMediaItemsBitmapMaxSize(mContent.getContext());

        List<CarUiContentListItem> data = new ArrayList<>();
        CarUiListItemAdapter adapter = new CarUiListItemAdapter(data);
        AlertDialog dialog =
                new AlertDialogBuilder(mContent.getContext())
                        .setAdapter(adapter)
                        .setCancelable(true)
                        .create();
        dialog.setOnDismissListener(v ->
                sendBrowseCustomActionEvent(mediaId, overflowActions, false));

        for (CustomBrowseAction customBrowseAction : overflowActions) {
            CarUiContentListItem item = new CarUiContentListItem(CarUiContentListItem.Action.ICON);
            item.setPrimaryIconType(CarUiContentListItem.IconType.AVATAR);
            item.setTitle(customBrowseAction.getLabel());
            ImageBinder<ImageBinder.ImageRef> imageBinder =
                    new ImageBinder<>(
                            ImageBinder.PlaceholderType.FOREGROUND,
                            mMaxArtSize,
                            drawable -> {
                                item.setIcon(drawable);
                                adapter.notifyDataSetChanged();
                            });
            imageBinder.setImage(mContent.getContext(), customBrowseAction.getArtRef());
            item.setOnItemClickedListener(
                    (contentItem) -> {
                        sendBrowseCustomAction(customBrowseAction, mediaId);
                        dialog.dismiss();
                    });
            data.add(item);
        }
        dialog.show();
    }

    private boolean handleBrowseCustomActionsExtras(Bundle actionExtras) {
        boolean handled = false;

        if (actionExtras.containsKey(
                MediaConstants.BROWSE_CUSTOM_ACTIONS_EXTRA_RESULT_MESSAGE)) {
            handled = true;
            String text = actionExtras.getString(
                    MediaConstants.BROWSE_CUSTOM_ACTIONS_EXTRA_RESULT_MESSAGE);
            Toast.makeText(
                            getContent().getContext(),
                            text,
                            Toast.LENGTH_SHORT)
                    .show();
        }

        if (actionExtras.containsKey(
                MediaConstants.BROWSE_CUSTOM_ACTIONS_EXTRA_RESULT_OPEN_PLAYBACK)) {
            handled = true;
            mCallbacks.openPlaybackView();
        }

        if (actionExtras.containsKey(
                MediaConstants.BROWSE_CUSTOM_ACTIONS_EXTRA_RESULT_BROWSE_NODE)) {
            String mediaItemId =
                    actionExtras.getString(
                            MediaConstants.BROWSE_CUSTOM_ACTIONS_EXTRA_RESULT_BROWSE_NODE);
            if (!TextUtils.isEmpty(mediaItemId)) {
                handled = true;
                mMediaRepo.getItem(
                        mediaItemId,
                        new BrowseActionCallback() {
                            @Override
                            public void onItemLoaded(MediaItem item) {
                                mCallbacks.goToMediaItem(new MediaItemMetadata(item));
                            }
                        });
            }
        }

        if (actionExtras.containsKey(
                MediaConstants.BROWSE_CUSTOM_ACTIONS_EXTRA_RESULT_REFRESH_ITEM)) {
            String mediaItemId =
                    actionExtras.getString(
                            MediaConstants.BROWSE_CUSTOM_ACTIONS_EXTRA_RESULT_REFRESH_ITEM);
            if (!TextUtils.isEmpty(mediaItemId)) {
                handled = true;
                mMediaRepo.getItem(
                        mediaItemId,
                        new BrowseActionCallback() {
                            @Override
                            public void onItemLoaded(MediaItem item) {
                                handleActionItemRefreshed(item);
                            }
                        });
            }
        }
        return handled;
    }

    private void handleActionItemRefreshed(MediaItem item) {
        if (Objects.equals(item.getDescription().getMediaId(), mParentItem.getId())) {
            mParentActions =
                    BrowseAdapterUtils.buildBrowseCustomActions(
                            mContent.getContext(),
                            new MediaItemMetadata(item),
                            mGlobalActions);
            mActionBar.setActions(mParentActions);
        } else {
            mLimitedBrowseAdapter.updateItemMetaData(
                    new MediaItemMetadata(item),
                    BrowseAdapter.MediaItemUpdateType.BROWSE_ACTIONS);
        }
    }

    /**
     * @param action - action that was invoked
     * @param extras - Sent to client
     * @param resultData - Returned from Client
     */
    private void handleBrowseCustomActionResult(String action, Bundle extras, Bundle resultData) {
        if (!handleBrowseCustomActionsExtras(resultData)) {
            Log.v(TAG, "Unhandled Action Result: " + action);
        }
        String mediaItemId = extras.getString(MediaConstants.BROWSE_CUSTOM_ACTIONS_MEDIA_ITEM_ID);
        Log.v(TAG, String.format("Action Result: %s from item: %s", action, mediaItemId));
    }

    private void handleSourceUpdates(Pair<MediaSource, MediaSource> mediaSourceMediaSourcePair) {
        // If sources are the same, make sure we aren't showing the mini item bar.
        if (isSourcesSame()) {
            hideEmptyListPlayItem();
        }
        if (mediaSourceMediaSourcePair.second != null && mActionBar != null) {
            CharSequence browseSourceName = mediaSourceMediaSourcePair.second.getDisplayName(
                    getActivity());
            mActionBar.setTitle(browseSourceName);
        }
    }

    /** Shares the browse adapter with the given view... #local-hack. */
    public void shareBrowseAdapterWith(RecyclerView view) {
        view.setAdapter(mLimitedBrowseAdapter);
    }

    private final Observer<FutureData<List<MediaItemMetadata>>> mItemsObserver =
            this::onItemsUpdate;

    View getContent() {
        return mContent;
    }

    String getDebugInfo() {
        StringBuilder log = new StringBuilder();
        log.append("[");
        log.append((mParentItem != null) ? mParentItem.getTitle() : "Search");
        log.append("]");
        FutureData<List<MediaItemMetadata>> children = mMediaItems.getValue();
        if (children == null) {
            log.append(" null future data");
        } else if (children.isLoading()) {
            log.append(" loading");
        } else if (children.getData() == null) {
            log.append(" null list");
        } else {
            List<MediaItemMetadata> nodes = children.getData();
            log.append(" ");
            log.append(nodes.size());
            log.append(" {");
            if (nodes.size() > 0) {
                log.append(nodes.get(0).getTitle().toString());
            }
            if (nodes.size() > 1) {
                log.append(", ");
                log.append(nodes.get(1).getTitle().toString());
            }
            if (nodes.size() > 2) {
                log.append(", ...");
            }
            log.append(" }");
        }
        return log.toString();
    }

    void destroy() {
        mCallbacks.getActivity().getLifecycle().removeObserver(mUxrContentLimiter);
        mMediaItems.removeObserver(mItemsObserver);
        removeFromParent(mContent);
    }

    private Runnable mLoadingIndicatorRunnable = new Runnable() {
        @Override
        public void run() {
            mMessage.setText(R.string.browser_loading);
            ViewUtils.showViewAnimated(mMessage, mFadeDuration);
        }
    };

    private void startLoadingIndicator() {
        // Display the indicator after a certain time, to avoid flashing the indicator constantly,
        // even when performance is acceptable.
        mHandler.postDelayed(mLoadingIndicatorRunnable, mLoadingIndicatorDelay);
    }

    private void stopLoadingIndicator() {
        mHandler.removeCallbacks(mLoadingIndicatorRunnable);
        ViewUtils.hideViewAnimated(mMessage, mFadeDuration);
    }

    public void onCarUiInsetsChanged(@NonNull Insets insets) {
        int actionHeaderOffset = 0;
        if (mActionBar != null && mActionBar.isShown()) {
            Resources res = getActivity().getResources();
            actionHeaderOffset = res.getDimensionPixelSize(R.dimen.media_browse_header_item_height);
        }
        int leftPadding = mBrowseList.getPaddingLeft();
        int rightPadding = mBrowseList.getPaddingRight();
        int bottomPadding = mBrowseList.getPaddingBottom() + actionHeaderOffset;
        int topPadding = insets.getTop() + actionHeaderOffset;
        mBrowseList.setPadding(leftPadding, topPadding, rightPadding, bottomPadding);
        if (bottomPadding > mFocusAreaHighlightBottomPadding) {
            mFocusAreaHighlightBottomPadding = bottomPadding;
        }
        mFocusArea.setHighlightPadding(
                leftPadding, topPadding, rightPadding, mFocusAreaHighlightBottomPadding);
        mFocusArea.setBoundsOffset(leftPadding, topPadding, rightPadding, bottomPadding);
    }

    /** Should preferably be called on the SearchResultsController. */
    void updateSearchQuery(@Nullable String query) {
        Bundle options = createItemSubscriptionOptions(mContainer, mBrowseList);
        mMediaRepo.setSearchQuery(query, options);
    }

    void onPlaybackControlsChanged(boolean visible) {
        int leftPadding = mBrowseList.getPaddingLeft();
        int topPadding = mBrowseList.getPaddingTop();
        int rightPadding = mBrowseList.getPaddingRight();
        Resources res = getActivity().getResources();
        int bottomPadding = visible
                ? res.getDimensionPixelOffset(R.dimen.browse_fragment_bottom_padding) : 0;
        mBrowseList.setPadding(leftPadding, topPadding, rightPadding, bottomPadding);
        int highlightBottomPadding = mSetFocusAreaHighlightBottom ? bottomPadding : 0;
        if (highlightBottomPadding > mFocusAreaHighlightBottomPadding) {
            mFocusAreaHighlightBottomPadding = highlightBottomPadding;
        }
        mFocusArea.setHighlightPadding(
                leftPadding, topPadding, rightPadding, mFocusAreaHighlightBottomPadding);
        // Set the bottom offset to bottomPadding regardless of mSetFocusAreaHighlightBottom so that
        // RotaryService can find the correct target when the user nudges the rotary controller.
        mFocusArea.setBoundsOffset(leftPadding, topPadding, rightPadding, bottomPadding);

        ViewGroup.MarginLayoutParams messageLayout =
                (ViewGroup.MarginLayoutParams) mMessage.getLayoutParams();
        messageLayout.bottomMargin = bottomPadding;
        mMessage.setLayoutParams(messageLayout);
    }

    private String getErrorMessage() {
        if (/*root*/ !mDisplayMediaItems) {
            MediaSource mediaSource = mViewModel.getMediaSourceValue();
            return getActivity().getString(
                    R.string.cannot_connect_to_app,
                    mediaSource != null
                            ? mediaSource.getDisplayName(getActivity())
                            : getActivity().getString(
                                    R.string.unknown_media_provider_name));
        } else {
            return getActivity().getString(R.string.unknown_error);
        }
    }

    private void onItemsUpdate(@Nullable FutureData<List<MediaItemMetadata>> futureData) {
        if (futureData == null || futureData.isLoading()) {
            ViewUtils.hideViewAnimated(mErrorIcon, 0);
            ViewUtils.hideViewAnimated(mMessage, 0);

            // TODO(b/139759881) build a jank-free animation of the transition.
            mBrowseList.setAlpha(0f);
            mLimitedBrowseAdapter.submitItems(null, null);

            if (futureData != null) {
                startLoadingIndicator();
            }
            return;
        }

        stopLoadingIndicator();

        List<MediaItemMetadata> items = MediaBrowserViewModelImpl.filterItems(
                /*root*/ !mDisplayMediaItems, futureData.getData());

        boolean sourceHasPlayable = mViewModel.hasPlayableItem();
        if (items != null && !sourceHasPlayable) {
            mViewModel.setHasPlayableItem(items.stream().anyMatch(MediaItemMetadata::isPlayable));
        }

        boolean hasMetaData = mPlaybackViewModelBrowseSource.getMetadata().getValue() != null;

        boolean hasPlayCommand = false;
        if (mPlaybackViewModelBrowseSource.getPlaybackStateWrapper().getValue() != null) {
            hasPlayCommand = (mPlaybackViewModelBrowseSource.getPlaybackStateWrapper().getValue()
                    .getSupportedActions() & PlaybackStateCompat.ACTION_PLAY) != 0;
        }

        if (mDisplayMediaItems) {
            mLimitedBrowseAdapter.submitItems(mParentItem, items);

            List<MediaItemMetadata> lastNodes =
                    MediaBrowserViewModelImpl.selectBrowseableItems(futureData.getPastData());
            Collection<MediaItemMetadata> removedNodes =
                    MediaBrowserViewModelImpl.computeRemovedItems(lastNodes, items);
            if (!removedNodes.isEmpty()) {
                mCallbacks.onChildrenNodesRemoved(this, removedNodes);
            }
        }

        int duration = mFadeDuration;
        if (items == null) {
            mMessage.setText(getErrorMessage());
            ViewUtils.hideViewAnimated(mBrowseList.getView(), duration);
            ViewUtils.showViewAnimated(mMessage, duration);
            ViewUtils.showViewAnimated(mErrorIcon, duration);
            hideEmptyListPlayItem();
        } else if (items.isEmpty()) {
            boolean shouldShowEmptyListPlayItem =
                    !isSourcesSame()
                            && !mViewModel.hasPlayableItem()
                            && hasMetaData
                            && hasPlayCommand;
            if (shouldShowEmptyListPlayItem) {
                ViewUtils.hideViewAnimated(mBrowseList.getView(), duration);
                ViewUtils.hideViewAnimated(mErrorIcon, duration);
                ViewUtils.hideViewAnimated(mMessage, duration);
                showEmptyListPlayItem();
            } else {
                mMessage.setText(R.string.nothing_to_play);
                ViewUtils.hideViewAnimated(mBrowseList.getView(), duration);
                ViewUtils.hideViewAnimated(mErrorIcon, duration);
                ViewUtils.showViewAnimated(mMessage, duration);
                hideEmptyListPlayItem();
            }
        } else {
            ViewUtils.showViewAnimated(mBrowseList.getView(), duration);
            ViewUtils.hideViewAnimated(mErrorIcon, duration);
            ViewUtils.hideViewAnimated(mMessage, duration);
            hideEmptyListPlayItem();
        }

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "onItemsUpdate " + getDebugInfo());
        }
    }

    private boolean isSourcesSame() {
        PlaybackViewModel playbackViewModelBrowse =
                mViewModel.getPlaybackViewModel(MEDIA_SOURCE_MODE_BROWSE);
        PlaybackViewModel playbackViewModelPlayback =
                mViewModel.getPlaybackViewModel(MEDIA_SOURCE_MODE_PLAYBACK);

        return Objects.equals(playbackViewModelPlayback.getMediaSource().getValue(),
                playbackViewModelBrowse.getMediaSource().getValue());
    }

    private void showEmptyListPlayItem() {
        if (mEmptyListPlaybackBar == null) {
            View inflatedView = LayoutInflater.from(getActivity())
                    .inflate(R.layout.browse_mini_bar_container, (ViewGroup) getContent());
            mEmptyListPlaybackBar = inflatedView.findViewById(R.id.browse_mini_item_bar);
        }

        ViewUtils.showViewAnimated(mEmptyListPlaybackBar, mFadeDuration);

        Size maxArtSize = MediaAppConfig.getMediaItemsBitmapMaxSize(mContent.getContext());
        PlaybackViewModel playViewModel = mViewModel.getPlaybackViewModel(MEDIA_SOURCE_MODE_BROWSE);
        mEmptyListPlaybackBar.setModel(playViewModel, getActivity(), maxArtSize);

        mEmptyListPlaybackBar.setOnClickListener(
                view -> mCallbacks.onBrowseEmptyListPlayItemClicked());
    }

    private void hideEmptyListPlayItem() {
        if (mEmptyListPlaybackBar != null) {
            ViewUtils.hideViewAnimated(mEmptyListPlaybackBar, mFadeDuration);
        }
    }

    private void handleProgressUpdate(Pair<PlaybackProgress, MediaItemMetadata> progressMetaPair) {
        if (progressMetaPair.first == null
                || progressMetaPair.second == null
                || mLimitedBrowseAdapter == null) {
            return;
        }

        // Checks if sources are the same before updating adapter with progress updates.
        MediaSource browseSource =  mViewModel.getMediaSourceValue();
        MediaSource playSource = mPlaybackViewModel.getMediaSource().getValue();

        if (browseSource != null && playSource != null && browseSource.equals(playSource)) {
            String mediaId = progressMetaPair.second.getId();
            MediaItemMetadata adapterMetaData = mLimitedBrowseAdapter.getMediaByMetaData(mediaId);
            if (adapterMetaData != null) {
                double progress = progressMetaPair.first.getProgressFraction();
                adapterMetaData.setProgress(progress);
                mLimitedBrowseAdapter.updateItemMetaData(adapterMetaData,
                        BrowseAdapter.MediaItemUpdateType.PROGRESS);
            }
        } else {
            // Ignore, playback app is not the same as browse app, therefore no UI update needed.
        }
    }
}
