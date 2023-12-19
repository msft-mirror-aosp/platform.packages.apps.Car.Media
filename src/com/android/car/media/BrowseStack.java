/*
 * Copyright 2023 The Android Open Source Project
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


import android.support.v4.media.MediaBrowserCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.mediaextensions.analytics.event.BrowseChangeEvent;

import com.android.car.media.common.MediaItemMetadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Stack;
import java.util.function.Predicate;

/**
 * The Browse stack maintains a history of the various media items the user has been exploring, so
 * that some navigation steps can easily be undone.
 *
 * A new entry is pushed onto the stack when the user selects one of the visible items.
 *
 * Each entry in the stack is decorated with a {@link BrowseEntryType}. The types are grouped into
 * three categories that identify the origin of the media items:
 *   - TREE:   the {@link MediaBrowserCompat}'s root.
 *   - SEARCH: a search query
 *   - LINK:   a linked item
 * Note that media apps decide whether searched and linked items are also part of the browse tree.
 *
 * The fist element of the stack must be a {@link BrowseEntryType#TREE_ROOT} and be the only one of
 * that type. If the stack contains a {@link BrowseEntryType#TREE_TAB}, it must be the second
 * element (call {@link #insertRootTab} to add it).
 */
public class BrowseStack {

    private static final String TAG = "BrowseStack";

    public enum BrowseEntryType {
        /** The {@link MediaBrowserCompat}'s root. */
        TREE_ROOT,
        /** The currently selected tab (one of the children of the root). */
        TREE_TAB,
        /** A descendant of the currently selected tab. */
        TREE_BROWSE,
        /** The list of search results. */
        SEARCH_RESULTS,
        /** An item descending from one of the search results. */
        SEARCH_BROWSE,
        /** An item that was linked to, regardless of the modality (NPV or Browse action). */
        LINK,
        /** An item descending from a linked entry. */
        LINK_BROWSE;

        /** Converts the entry type to the equivalent analytics browse mode. */
        @BrowseChangeEvent.BrowseMode
        public int toAnalyticBrowseMode() {
            switch (this) {
                case TREE_TAB:
                    return BrowseChangeEvent.BROWSE_MODE_TREE_TAB;
                case TREE_ROOT:
                    return BrowseChangeEvent.BROWSE_MODE_TREE_ROOT;
                case TREE_BROWSE:
                    return BrowseChangeEvent.BROWSE_MODE_TREE_BROWSE;
                case SEARCH_RESULTS:
                    return BrowseChangeEvent.BROWSE_MODE_SEARCH_RESULTS;
                case SEARCH_BROWSE:
                    return BrowseChangeEvent.BROWSE_MODE_SEARCH_BROWSE;
                case LINK:
                    return BrowseChangeEvent.BROWSE_MODE_LINK;
                case LINK_BROWSE:
                    return BrowseChangeEvent.BROWSE_MODE_LINK_BROWSE;
                default:
                    Log.e(TAG, "Unexpected BrowseEntryType");
                    return BrowseChangeEvent.BROWSE_MODE_UNKNOWN;
            }
        }

        BrowseEntryType getNextEntryBrowseType() {
            switch (this) {
                case TREE_ROOT:
                    Log.e(TAG, "getNextEntryBrowseType should not be called on the root");
                    // fallthrough
                case TREE_TAB:
                case TREE_BROWSE:
                    return TREE_BROWSE;
                case SEARCH_RESULTS:
                case SEARCH_BROWSE:
                    return SEARCH_BROWSE;
                case LINK:
                case LINK_BROWSE:
                    return LINK_BROWSE;
                default:
                    Log.e(TAG, "getNextEntryBrowseType doesn't know about: " + this);
                    return TREE_BROWSE;
            }
        }
    }

    /**
     * Records the key elements of a stack entry:
     *    - {@link BrowseEntryType} its type
     *    - {@link MediaItemMetadata}
     *        + null for {@link BrowseEntryType#TREE_ROOT} | @link BrowseEntryType#SEARCH_RESULTS}.
     *        + otherwise non null, the item displayed at this level of the stack
     *    - {@link BrowseViewController} the controller in charge of this level. Note that
     *        controllers must be destroyed and recreated on UI configuration changes.
     */
    static class BrowseEntry {
        final BrowseEntryType mType;
        @Nullable final MediaItemMetadata mItem;
        private @Nullable BrowseViewController mController;

        private BrowseEntry(@NonNull BrowseEntryType type, @Nullable MediaItemMetadata item,
                @NonNull BrowseViewController controller) {
            mType = type;
            mItem = item;
            mController = controller;
        }

        @Nullable BrowseViewController getController() {
            return mController;
        }

        void destroyController() {
            if (mController != null) {
                mController.destroy();
                mController = null;
            }
        }

        void setRecreatedController(@NonNull BrowseViewController controller) {
            mController = controller;
        }
    }

    private final Stack<BrowseEntry> mEntries = new Stack<>();

    BrowseStack() {
    }

    /** Returns the number of entries in the stack. */
    int size() {
        return mEntries.size();
    }

    void pushRoot(MediaItemMetadata fakeRootItem, @NonNull BrowseViewController controller) {
        if (mEntries.isEmpty()) {
            mEntries.push(new BrowseEntry(BrowseEntryType.TREE_ROOT, fakeRootItem, controller));
        } else {
            Log.e(TAG, "Ignoring pushRoot on a non empty stack.");
        }
    }

    String getRootId() {
        if (mEntries.isEmpty()) return null;

        BrowseEntry first = mEntries.get(0);
        if (first.mItem == null || first.mType != BrowseEntryType.TREE_ROOT) {
            return null;
        }
        return first.mItem.getId();
    }

    void pushSearchResults(@NonNull BrowseViewController controller) {
        mEntries.push(new BrowseEntry(BrowseEntryType.SEARCH_RESULTS, null, controller));
    }

    void pushEntry(@NonNull BrowseEntryType type, @NonNull MediaItemMetadata item,
            @NonNull BrowseViewController controller) {
        mEntries.push(new BrowseEntry(type, item, controller));
    }

    /** Inserts a tab at the start of the stack. */
    void insertRootTab(@NonNull MediaItemMetadata item, @NonNull BrowseViewController ctrl) {
        if (mEntries.isEmpty() || (BrowseEntryType.TREE_ROOT != mEntries.get(0).mType)) {
            Log.e(TAG, "insertRootTab must be called AFTER adding a root.");
        } else {
            mEntries.insertElementAt(new BrowseEntry(BrowseEntryType.TREE_TAB, item, ctrl), 1);
        }
    }

    @Nullable
    BrowseEntry peek() {
        return mEntries.isEmpty() ? null : mEntries.peek();
    }

    BrowseEntry pop() {
        return mEntries.pop();
    }

    /** Returns the current controller being displayed. */
    @Nullable
    BrowseViewController getCurrentController() {
        return mEntries.isEmpty() ? null : mEntries.peek().mController;
    }

    /** Returns the {@link BrowseEntryType} of the entry at the top of the stack. */
    @Nullable
    BrowseEntryType getCurrentEntryType() {
        return mEntries.isEmpty() ? null : mEntries.peek().mType;
    }

    /** Returns the current item being displayed. */
    @Nullable
    MediaItemMetadata getCurrentMediaItem() {
        return mEntries.isEmpty() ? null : mEntries.peek().mItem;
    }

    boolean isShowingSearchResults() {
        return (getCurrentEntryType() == BrowseEntryType.SEARCH_RESULTS);
    }

    List<BrowseEntry> getEntries() {
        return Collections.unmodifiableList(mEntries);
    }

    List<BrowseEntry> removeAllEntriesExceptRoot() {
        List<BrowseEntry> result = new ArrayList<>(mEntries.size());
        List<BrowseEntry> sublist = mEntries.subList(1, mEntries.size());
        result.addAll(sublist);
        sublist.clear();
        return result;
    }

    List<BrowseEntry> removeTreeEntriesExceptRoot() {
        return removeEntries(entry -> (entry.mType == BrowseEntryType.TREE_TAB
                || entry.mType == BrowseEntryType.TREE_BROWSE));
    }

    List<BrowseEntry> removeSearchEntries() {
        return removeEntries(entry -> (entry.mType == BrowseEntryType.SEARCH_RESULTS
                || entry.mType == BrowseEntryType.SEARCH_BROWSE));
    }

    List<BrowseEntry> removeObsoleteEntries(@NonNull BrowseViewController controller,
            @NonNull Collection<MediaItemMetadata> removedChildren) {
        List<BrowseEntry> result = new ArrayList<>();
        int ctrlIndex = -1;
        BrowseEntryType typeToRemove = null;
        for (int index = 0; index < mEntries.size(); index++) {
            if (controller == mEntries.get(index).mController) {
                ctrlIndex = index;
                typeToRemove = mEntries.get(index).mType.getNextEntryBrowseType();
                break;
            }
        }

        if (ctrlIndex < 0 || ctrlIndex >= mEntries.size() - 1) {
            // Controller not found or it has no child in the stack.
            return result;
        }

        final int firstChild = ctrlIndex + 1;
        if (removedChildren.contains(mEntries.get(firstChild).mItem)) {
            int endRange = ctrlIndex + 2;
            while (endRange < mEntries.size() && mEntries.get(endRange).mType == typeToRemove) {
                endRange++;
            }
            List<BrowseEntry> sublist = mEntries.subList(firstChild, endRange);
            result.addAll(sublist);
            sublist.clear();
        }
        return result;
    }

    private List<BrowseEntry> removeEntries(@NonNull Predicate<BrowseEntry> shouldRemove) {
        List<BrowseEntry> result = new ArrayList<>(mEntries.size());
        Stack<BrowseEntry> kept = new Stack<>();
        for (BrowseEntry entry: mEntries) {
            if (shouldRemove.test(entry)) {
                result.add(entry);
            } else {
                kept.push(entry);
            }
        }
        mEntries.clear();
        mEntries.addAll(kept);
        return result;
    }
}
