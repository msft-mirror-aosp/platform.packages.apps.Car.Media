/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.car.media.browse.BrowseTestUtils.generateParentItem;
import static com.android.car.media.browse.BrowseTestUtils.generateTestItems;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;

import static org.mockito.Mockito.*;

import android.content.Context;

import androidx.media.utils.MediaConstants;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.media.BaseMockitoTest;
import com.android.car.media.common.MediaItemMetadata;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


@RunWith(AndroidJUnit4.class)
public class BrowseAdapterTests extends BaseMockitoTest {

    private BrowseAdapter mBrowseAdapter;

    @Mock
    private BrowseAdapter.OnListChangedListener mOnListChangedListener;

    @Mock
    private BrowseAdapter.Observer mObserver;

    @Captor
    ArgumentCaptor<List<BrowseViewData>> mPrevListCaptor;
    @Captor
    ArgumentCaptor<List<BrowseViewData>> mCurrListCaptor;


    @Before
    public void setup() {
        Context context = ApplicationProvider.getApplicationContext();
        mBrowseAdapter = new BrowseAdapter(context);
        mBrowseAdapter.setGlobalCustomActions(Collections.emptyMap());
    }

    @Test
    public void currentListChangedCallback() {
        mBrowseAdapter.setOnListChangedListener(mOnListChangedListener);

        ArrayList<BrowseViewData> testListPrev = new ArrayList<>();
        testListPrev.add(
                new BrowseViewData(
                        "Previous BVD list item", BrowseItemViewType.LIST_ITEM, null, null));
        mBrowseAdapter.onCurrentListChanged(null, testListPrev);

        ArrayList<BrowseViewData> testListCurr = new ArrayList<>();
        testListCurr.add(
                new BrowseViewData(
                        "Current BVD grid item", BrowseItemViewType.GRID_ITEM, null, null));
        mBrowseAdapter.onCurrentListChanged(testListPrev, testListCurr);

        verify(mOnListChangedListener, atLeast(1)).onListChanged(mPrevListCaptor.capture(),
                mCurrListCaptor.capture());
        assertEquals(mPrevListCaptor.getValue(), testListPrev);
        assertEquals(mCurrListCaptor.getValue(), testListCurr);
    }

    @Test
    public void submitItemsGeneratesCorrectData() {
        mBrowseAdapter.setOnListChangedListener(mOnListChangedListener);
        mBrowseAdapter.submitItems(generateParentItem(), generateTestItems());
        List<BrowseViewData> items = mBrowseAdapter.getCurrentList();

        assertNotNull(items);
        assertEquals(generateTestItems().size(), items.size());
        assertEquals("TestItem1", items.get(0).mMediaItem.getTitle());
        assertEquals("TestItem2", items.get(1).mMediaItem.getTitle());
    }

    @Test
    public void titleAddedToList() {
        String testString = "Test Title";
        mBrowseAdapter.setOnListChangedListener(mOnListChangedListener);
        mBrowseAdapter.setTitle(testString);
        mBrowseAdapter.submitItems(generateParentItem(), new ArrayList<>(0));

        List<BrowseViewData> items = mBrowseAdapter.getCurrentList();

        assertNotNull(items);
        assertEquals(items.get(0).mText, testString);
    }

    @Test
    public void addDefaultViewTypes() {
        //Sets root browsable view type default to GRID_ITEM
        mBrowseAdapter.setRootBrowsableViewType(
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM);
        //Sets root playable view type default to ICON_LIST_ITEM
        mBrowseAdapter.setRootPlayableViewType(
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM);

        List<MediaItemMetadata> itemList = generateTestItems();
        mBrowseAdapter.submitItems(generateParentItem(), itemList);
        List<BrowseViewData> items = mBrowseAdapter.getCurrentList();

        //Default is list, root set to GRID
        assertEquals(BrowseItemViewType.GRID_ITEM, items.get(0).mViewType);
        //Default is list, root set to ICON_LIST
        assertEquals(BrowseItemViewType.ICON_LIST_ITEM, items.get(1).mViewType);
    }

    @Test
    public void getSpanSize() {
        //Set default browse to Grid, Grid has a default span size of 1
        mBrowseAdapter.setRootBrowsableViewType(
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM);

        List<MediaItemMetadata> itemList = generateTestItems();
        mBrowseAdapter.setTitle("Title");
        mBrowseAdapter.submitItems(generateParentItem(), itemList);

        //Title doesn't have size, so it defaults to max
        assertEquals(2, mBrowseAdapter.getSpanSize(0, 2));
        //GRID defaults to 1, supplied max should be ignored.
        assertEquals(1, mBrowseAdapter.getSpanSize(1, 3));
    }

    @Test
    public void observersNotify() {
        List<MediaItemMetadata> itemList = generateTestItems();
        mBrowseAdapter.registerObserver(mObserver);
        mBrowseAdapter.setTitle("Title");
        mBrowseAdapter.submitItems(generateParentItem(), itemList);

        List<BrowseViewData> items = mBrowseAdapter.getCurrentList();
        items.get(0).mCallback.onMediaItemClicked(items.get(0));
        items.get(1).mCallback.onMediaItemClicked(items.get(1));
        items.get(2).mCallback.onMediaItemClicked(items.get(2));

        verify(mObserver, atLeast(1)).onHeaderItemClicked();
        verify(mObserver, atLeast(1)).onBrowsableItemClicked(any());
        verify(mObserver, atLeast(1)).onPlayableItemClicked(any());
    }
}
