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

package com.android.car.media.browse;

import android.content.Context;
import android.support.v4.media.MediaDescriptionCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.utils.MediaConstants;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.media.common.MediaItemMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A {@link RecyclerView.Adapter} that can be used to display a single level of a {@link
 * android.service.media.MediaBrowserService} media tree into a {@link
 * androidx.car.widget.PagedListView} or any other {@link RecyclerView}.
 *
 * <p>This adapter assumes that the attached {@link RecyclerView} uses a {@link GridLayoutManager},
 * as it can use both grid and list elements to produce the desired representation.
 *
 * <p>Consumers of this adapter should use {@link #registerObserver(Observer)} to receive updates.
 */
public class BrowseAdapter extends ListAdapter<BrowseViewData, BrowseViewHolder> {
    private static final String TAG = "BrowseAdapter";

    /**
     * Listens to the list data changes.
     */
    public interface OnListChangedListener {
        /**
         * Called when {@link #onCurrentListChanged(List, List)} is called.
         */
        void onListChanged(List<BrowseViewData> previousList, List<BrowseViewData> currentList);
    }

    @NonNull
    private final Context mContext;
    @NonNull
    private List<Observer> mObservers = new ArrayList<>();
    @Nullable
    private CharSequence mTitle;
    @Nullable
    private MediaItemMetadata mParentMediaItem;

    private BrowseItemViewType mRootBrowsableViewType = BrowseItemViewType.LIST_ITEM;
    private BrowseItemViewType mRootPlayableViewType = BrowseItemViewType.LIST_ITEM;

    private OnListChangedListener mOnListChangedListener;

    private static final DiffUtil.ItemCallback<BrowseViewData> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<BrowseViewData>() {
                @Override
                public boolean areItemsTheSame(@NonNull BrowseViewData oldItem,
                        @NonNull BrowseViewData newItem) {
                    return Objects.equals(
                            oldItem.mMediaItem != null ? oldItem.mMediaItem.getId() : null,
                            newItem.mMediaItem != null ? newItem.mMediaItem.getId() : null)
                            && Objects.equals(oldItem.mText, newItem.mText);
                }

                @Override
                public boolean areContentsTheSame(@NonNull BrowseViewData oldItem,
                        @NonNull BrowseViewData newItem) {
                    return Objects.equals(oldItem, newItem);
                }

                @Nullable
                @Override
                public Object getChangePayload(@NonNull BrowseViewData oldItem,
                        @NonNull BrowseViewData newItem) {
                    if (oldItem == newItem || Objects.equals(oldItem.mUpdatedMediaItem,
                            newItem.mUpdatedMediaItem)) {
                        return super.getChangePayload(oldItem, newItem);
                    } else {
                        return newItem.mUpdatedMediaItem;
                    }
                }
            };

    /**
     * An {@link BrowseAdapter} observer.
     */
    public static abstract class Observer {

        /**
         * Callback invoked when a user clicks on a playable item.
         */
        protected void onPlayableItemClicked(@NonNull MediaItemMetadata item) {
        }

        /**
         * Callback invoked when a user clicks on a browsable item.
         */
        protected void onBrowsableItemClicked(@NonNull MediaItemMetadata item) {
        }

        /**
         * Callback invoked when the user clicks on the title of the queue.
         */
        protected void onTitleClicked() {
        }
    }

    /**
     * Creates a {@link BrowseAdapter} that displays the children of the given media tree node.
     */
    public BrowseAdapter(@NonNull Context context) {
        super(DIFF_CALLBACK);
        mContext = context;
    }

    /**
     * Sets title to be displayed.
     */
    public void setTitle(CharSequence title) {
        mTitle = title;
    }

    /**
     * Registers an {@link Observer}
     */
    public void registerObserver(Observer observer) {
        mObservers.add(observer);
    }

    /**
     * Unregisters an {@link Observer}
     */
    public void unregisterObserver(Observer observer) {
        mObservers.remove(observer);
    }

    public void setRootBrowsableViewType(int hintValue) {
        mRootBrowsableViewType = fromMediaHint(hintValue);
    }

    public void setRootPlayableViewType(int hintValue) {
        mRootPlayableViewType = fromMediaHint(hintValue);
    }

    public int getSpanSize(int position, int maxSpanSize) {
        BrowseItemViewType viewType = getItem(position).mViewType;
        return viewType.getSpanSize(maxSpanSize);
    }

    /**
     * Sets a listener to listen for the list data changes.
     */
    public void setOnListChangedListener(OnListChangedListener onListChangedListener) {
        mOnListChangedListener = onListChangedListener;
    }

    @NonNull
    @Override
    public BrowseViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layoutId = BrowseItemViewType.values()[viewType].getLayoutId();
        View view = LayoutInflater.from(mContext).inflate(layoutId, parent, false);
        return new BrowseViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull BrowseViewHolder holder, int position) {
        onBindViewHolder(holder, position, new ArrayList<>());
    }

    @Override
    public void onBindViewHolder(@NonNull BrowseViewHolder holder, int position,
            @NonNull List<Object> payloads) {
        //We are only checking for MediaMetaData for now, since this is the only payload we are
        // setting in getChangePayload
        if (payloads.isEmpty() || !(payloads.get(0) instanceof MediaItemMetadata)) {
            BrowseViewData viewData = getItem(position);
            holder.bind(mContext, viewData);
        } else {
            MediaItemMetadata mediaMetadata = (MediaItemMetadata) payloads.get(0);
            holder.update(mediaMetadata);
        }
    }

    @Override
    public void onViewAttachedToWindow(@NonNull BrowseViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        holder.onViewAttachedToWindow(mContext);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull BrowseViewHolder holder) {
        super.onViewDetachedFromWindow(holder);
        holder.onViewDetachedFromWindow(mContext);
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).mViewType.ordinal();
    }

    @Override
    public void onCurrentListChanged(@NonNull List<BrowseViewData> previousList,
            @NonNull List<BrowseViewData> currentList) {
        super.onCurrentListChanged(previousList, currentList);
        if (mOnListChangedListener != null) {
            mOnListChangedListener.onListChanged(previousList, currentList);
        }
    }

    public void submitItems(@Nullable MediaItemMetadata parentItem,
            @Nullable List<MediaItemMetadata> children) {
        mParentMediaItem = parentItem;
        if (children == null) {
            submitList(Collections.emptyList());
            return;
        }
        submitList(generateViewData(children));
    }

    private void notify(Consumer<Observer> notification) {
        for (Observer observer : mObservers) {
            notification.accept(observer);
        }
    }

    private class ItemsBuilder {
        private List<BrowseViewData> result = new ArrayList<>();

        void addItem(MediaItemMetadata item,
                BrowseItemViewType viewType, Consumer<Observer> notification) {
            View.OnClickListener listener = notification != null ?
                    view -> BrowseAdapter.this.notify(notification) :
                    null;
            result.add(new BrowseViewData(item, viewType, listener));
        }

        void addTitle(CharSequence title, Consumer<Observer> notification) {
            if (title == null) {
                title = "";
            }
            View.OnClickListener listener = notification != null ?
                    view -> BrowseAdapter.this.notify(notification) :
                    null;
            result.add(new BrowseViewData(title, BrowseItemViewType.HEADER, listener));
        }

        void addSpacer() {
            result.add(new BrowseViewData(BrowseItemViewType.SPACER, null));
        }

        List<BrowseViewData> build() {
            return result;
        }
    }

    /**
     * Flatten the given collection of item states into a list of {@link BrowseViewData}s. To avoid
     * flickering, the flatting will stop at the first "loading" section, avoiding unnecessary
     * insertion animations during the initial data load.
     */
    private List<BrowseViewData> generateViewData(List<MediaItemMetadata> items) {
        ItemsBuilder itemsBuilder = new ItemsBuilder();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Generating browse view from:");
            for (MediaItemMetadata item : items) {
                Log.v(TAG, String.format("[%s%s] '%s' (%s)",
                        item.isBrowsable() ? "B" : " ",
                        item.isPlayable() ? "P" : " ",
                        item.getTitle(),
                        item.getId()));
            }
        }

        if (mTitle != null) {
            itemsBuilder.addTitle(mTitle, Observer::onTitleClicked);
        } else if (!items.isEmpty() && items.get(0).getTitleGrouping() == null) {
            itemsBuilder.addSpacer();
        }
        String currentTitleGrouping = null;
        for (MediaItemMetadata item : items) {
            String titleGrouping = item.getTitleGrouping();
            if (!Objects.equals(currentTitleGrouping, titleGrouping)) {
                currentTitleGrouping = titleGrouping;
                itemsBuilder.addTitle(titleGrouping, null);
            }
            if (item.isBrowsable()) {
                itemsBuilder.addItem(item, getBrowsableViewType(mParentMediaItem),
                        observer -> observer.onBrowsableItemClicked(item));
            } else if (item.isPlayable()) {
                itemsBuilder.addItem(item, getPlayableViewType(mParentMediaItem),
                        observer -> observer.onPlayableItemClicked(item));
            }
        }

        return itemsBuilder.build();
    }

    private BrowseItemViewType getBrowsableViewType(@Nullable MediaItemMetadata mediaItem) {
        if (mediaItem == null) {
            return BrowseItemViewType.LIST_ITEM;
        }
        if (mediaItem.getBrowsableContentStyleHint() == 0) {
            return mRootBrowsableViewType;
        }
        return fromMediaHint(mediaItem.getBrowsableContentStyleHint());
    }

    private BrowseItemViewType getPlayableViewType(@Nullable MediaItemMetadata mediaItem) {
        if (mediaItem == null) {
            return BrowseItemViewType.LIST_ITEM;
        }
        if (mediaItem.getPlayableContentStyleHint() == 0) {
            return mRootPlayableViewType;
        }
        return fromMediaHint(mediaItem.getPlayableContentStyleHint());
    }

    /**
     * Converts a content style hint to the appropriate {@link BrowseItemViewType}, defaulting to
     * list items.
     */
    private BrowseItemViewType fromMediaHint(int hint) {
        switch(hint) {
            case MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM:
                return BrowseItemViewType.GRID_ITEM;
            case MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM:
                return BrowseItemViewType.ICON_GRID_ITEM;
            case MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM:
                return BrowseItemViewType.ICON_LIST_ITEM;
            case MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM:
            default:
                return BrowseItemViewType.LIST_ITEM;
        }
    }

    /**
     * <p>
     * Grabs the {@link BrowseViewData} in adapter by mediaID
     * </p>
     * <p>
     * {@link BrowseViewData} has reference to {@link MediaItemMetadata}
     * {@link MediaItemMetadata} has reference to {@link MediaDescriptionCompat}
     * {@link MediaDescriptionCompat} has reference to media id {@link
     * MediaDescriptionCompat#getMediaId()}
     * </p>
     *
     * @return BrowseViewData
     */
    BrowseViewData getMediaByMetaData(String mediaID) {
        return getCurrentList()
                .stream()
                .filter(item -> {
                            if (item.mMediaItem != null) {
                                return Objects.equals(item.mMediaItem.getId(), mediaID);
                            } else {
                                return false;
                            }
                }
                )
                .findFirst()
                .orElse(null);
    }

    /**
     * <p>
     * This should call a partial bind with the new metadata as the diff payload,
     * meaning it will use bind with payload when view is visible or full bind when not.
     * the payload will then be used to only update the progress bar and not the
     * whole item's UI.
     * Use {@link androidx.recyclerview.widget.RecyclerView.AdapterDataObservable} to
     * listen to when there is a payload change called here.
     *
     * Check {@link ListAdapter#onBindViewHolder(RecyclerView.ViewHolder, int, List)}
     * to see more details on using a payload for a partial bind. Docs doesn't mention the need
     * for an observable, but you need it, otherwise it will do a full bind
     *
     * {@link DiffUtil} also uses partial binds on {@link ListAdapter#submitList(List)} when
     * we have the same item but with different content, it will then call
     * {@link DiffUtil.ItemCallback#getChangePayload(Object, Object)} which builds the diff
     * into a payload and passes it to
     * {@link ListAdapter#onBindViewHolder(RecyclerView.ViewHolder, int, List)}. 2 different
     * ways here where we can use a partial bind for performance.
     * </p>
     */
    void updateItemMetaData(MediaItemMetadata mediaItemMetadata) {
        BrowseViewData browseViewData = getMediaByMetaData(mediaItemMetadata.getId());
        if (browseViewData != null) {
            int position = getCurrentList().indexOf(browseViewData);
            browseViewData.mUpdatedMediaItem = mediaItemMetadata;
            notifyItemChanged(position, mediaItemMetadata);
        }
    }
}
