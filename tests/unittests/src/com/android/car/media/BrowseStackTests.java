/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE;

import static com.android.car.media.BrowseStack.BrowseEntryType.LINK;
import static com.android.car.media.BrowseStack.BrowseEntryType.LINK_BROWSE;
import static com.android.car.media.BrowseStack.BrowseEntryType.SEARCH_BROWSE;
import static com.android.car.media.BrowseStack.BrowseEntryType.SEARCH_RESULTS;
import static com.android.car.media.BrowseStack.BrowseEntryType.TREE_BROWSE;
import static com.android.car.media.BrowseStack.BrowseEntryType.TREE_ROOT;
import static com.android.car.media.BrowseStack.BrowseEntryType.TREE_TAB;

import static junit.framework.Assert.assertEquals;

import static org.mockito.Mockito.mock;

import android.support.v4.media.MediaMetadataCompat;

import androidx.core.util.Preconditions;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.media.BrowseStack.BrowseEntryType;
import com.android.car.media.common.MediaItemMetadata;

import com.google.common.collect.HashBiMap;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public class BrowseStackTests extends BaseMockitoTest {

    private final HashBiMap<BrowseEntryType, String> mTypeEncoding = HashBiMap.create();

    @Before
    public void setup() {
        mTypeEncoding.put(TREE_ROOT,        "TR");
        mTypeEncoding.put(TREE_TAB,         "TT");
        mTypeEncoding.put(TREE_BROWSE,      "TB");
        mTypeEncoding.put(SEARCH_RESULTS,   "SR");
        mTypeEncoding.put(SEARCH_BROWSE,    "SB");
        mTypeEncoding.put(LINK,             "LN");
        mTypeEncoding.put(LINK_BROWSE,      "LB");
    }

    @Test
    public void removeAllEntriesExceptRoot() {
        BrowseStack stack = decodeStack("TR/TT:tab/TB:n1/SR/SB:s1/SB:s2/");
        stack.removeAllEntriesExceptRoot();
        assertEquals("TR/", encodeStack(stack));
    }

    @Test
    public void removeTreeEntriesExceptRoot() {
        BrowseStack stack = decodeStack("TR/TT:tab/TB:n1/SR/SB:s1/SB:s2/LN:l1/LB:l2/");
        stack.removeTreeEntriesExceptRoot();
        assertEquals("TR/SR/SB:s1/SB:s2/LN:l1/LB:l2/", encodeStack(stack));
    }

    @Test
    public void removeTrailingSearchEntries() {
        BrowseStack stack = decodeStack("TR/TT:tab/TB:n1/SR/SB:s1/SB:s2/");
        stack.removeSearchEntries();
        assertEquals("TR/TT:tab/TB:n1/", encodeStack(stack));
    }

    @Test
    public void removeMiddleSearchEntries() {
        BrowseStack stack = decodeStack("TR/SR/SB:s1/SB:s2/LN:l1/LB:l2/");
        stack.removeSearchEntries();
        assertEquals("TR/LN:l1/LB:l2/", encodeStack(stack));
    }

    @Test
    public void removeTrailingObsoleteEntries() {
        BrowseStack stack = decodeStack("TR/SR/SB:s1/SB:s2/LN:l1/LB:l2/");
        removeObsoleteEntries(stack, 4, 5);
        assertEquals("TR/SR/SB:s1/SB:s2/LN:l1/", encodeStack(stack));
    }

    @Test
    public void removeObsoleteEntriesIgnoresIrrelevantItem() {
        BrowseStack stack = decodeStack("TR/SR/SB:s1/SB:s2/LN:l1/LB:l2/");
        removeObsoleteEntries(stack, 4, 2);
        assertEquals("TR/SR/SB:s1/SB:s2/LN:l1/LB:l2/", encodeStack(stack));
    }

    @Test
    public void removeMiddleObsoleteEntries() {
        BrowseStack stack = decodeStack("TR/TT:tab/TB:n1/TB:n2/TB:n3/SR/SB:s1/SB:s2/");
        removeObsoleteEntries(stack, 1, 2);
        assertEquals("TR/TT:tab/SR/SB:s1/SB:s2/", encodeStack(stack));
    }

    /**
     * Picks the controller and the item at the given index then calls removeObsoleteEntries.
     */
    private void removeObsoleteEntries(BrowseStack stack, int controllerIndex, int itemIndex) {
        BrowseViewController ctrl = stack.getEntries().get(controllerIndex).getController();
        ArrayList<MediaItemMetadata> list = new ArrayList<>(1);
        list.add(stack.getEntries().get(itemIndex).mItem);
        stack.removeObsoleteEntries(Preconditions.checkNotNull(ctrl), list);
    }

    private String encodeType(BrowseEntryType type) {
        return Preconditions.checkNotNull(mTypeEncoding.get(type));
    }

    private BrowseEntryType decodeType(String code) {
        return Preconditions.checkNotNull(mTypeEncoding.inverse().get(code));
    }

    private BrowseStack decodeStack(String encoded) {
        BrowseStack result = new BrowseStack();
        String[] entries = encoded.split("/");
        for (String entry : entries) {
            String[] codesAndNode = entry.split(":");
            BrowseEntryType type = decodeType(codesAndNode[0]);
            switch (type) {
                case TREE_ROOT:
                    MediaItemMetadata rootItem = MediaItemMetadata.createEmptyRootData("RootId");
                    result.pushRoot(rootItem, mock(BrowseViewController.class));
                    break;
                case SEARCH_RESULTS:
                    result.pushSearchResults(mock(BrowseViewController.class));
                    break;
                default:
                    MediaMetadataCompat.Builder bob = new MediaMetadataCompat.Builder();
                    bob.putText(METADATA_KEY_DISPLAY_TITLE, codesAndNode[1]);
                    MediaItemMetadata item = new MediaItemMetadata(bob.build());
                    result.pushEntry(type, item, mock(BrowseViewController.class));
            }
        }
        return result;
    }

    private String encodeStack(BrowseStack stack) {
        StringBuilder builder = new StringBuilder();
        for (BrowseStack.BrowseEntry entry : stack.getEntries()) {
            builder.append(encodeType(entry.mType));
            if ((entry.mType != TREE_ROOT) && (entry.mType != SEARCH_RESULTS)) {
                Preconditions.checkNotNull(entry.mItem);
                builder.append(":");
                builder.append(entry.mItem.getTitle());
            }
            builder.append("/");
        }
        return builder.toString();
    }
}
