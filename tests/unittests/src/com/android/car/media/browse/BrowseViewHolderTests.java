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

import static com.android.car.media.browse.BrowseItemViewType.ICON_LIST_ITEM;
import static com.android.car.media.browse.BrowseItemViewType.LIST_ITEM;
import static com.android.car.media.browse.BrowseTestUtils.generateTestItems;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.apps.common.imaging.ImageBinder;
import com.android.car.media.R;
import com.android.car.media.common.CustomBrowseAction;
import com.android.car.media.common.MediaItemMetadata;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class BrowseViewHolderTests {

    Context mContext;
    BrowseViewHolder mBrowseViewHolder;
    BrowseViewData mBrowseViewData;
    List<CustomBrowseAction> mCustomBrowseActions = Collections.emptyList();
    View mView;
    List<MediaItemMetadata> mItems;

    @Before
    public void setup() {
        mContext = ApplicationProvider.getApplicationContext();
        int layoutId = ICON_LIST_ITEM.getLayoutId();
        mView = LayoutInflater.from(mContext).inflate(layoutId, null, false);
        mBrowseViewHolder = new BrowseViewHolder(mView, ImageBinder.PlaceholderType.FOREGROUND);
        mItems = generateTestItems();
        mBrowseViewData = new BrowseViewData(mItems.get(0), LIST_ITEM, mCustomBrowseActions, null);
    }

    @Test
    public void onBindTitle() {
        mBrowseViewHolder.bind(mContext, mBrowseViewData);
        TextView title = mView.findViewById(R.id.title);
        assertEquals("TestItem1", title.getText().toString());
    }

    @Test
    public void onBindSubtitle() {
        mBrowseViewHolder.bind(mContext, mBrowseViewData);
        TextView subTitle = mView.findViewById(R.id.subtitle);
        assertEquals(View.VISIBLE, subTitle.getVisibility());
        assertEquals("Tester1", subTitle.getText().toString());
    }

    @Test
    public void onBindImagePlaceholder() {
        mBrowseViewHolder.bind(mContext, mBrowseViewData);
        ImageView imageView = mView.findViewById(R.id.thumbnail);
        assertNotNull(imageView.getDrawable());
    }

    @Test
    public void onBindBrowseIndicator() {
        mBrowseViewHolder.bind(mContext, mBrowseViewData);
        ImageView imageView = mView.findViewById(R.id.right_arrow);
        assertEquals(View.VISIBLE, imageView.getVisibility());
    }

    @Test
    public void onBindDownloaded() {
        mBrowseViewData = new BrowseViewData(mItems.get(2), LIST_ITEM, mCustomBrowseActions, null);
        mBrowseViewHolder.bind(mContext, mBrowseViewData);
        ImageView imageView = mView.findViewById(R.id.download_icon_with_title);
        ImageView imageViewSubtitle = mView.findViewById(R.id.download_icon_with_subtitle);
        assertEquals(View.VISIBLE, imageView.getVisibility());
        assertEquals(View.GONE, imageViewSubtitle.getVisibility());
    }

    @Test
    public void onBindExplicit() {
        mBrowseViewData = new BrowseViewData(mItems.get(2), LIST_ITEM, mCustomBrowseActions, null);
        mBrowseViewHolder.bind(mContext, mBrowseViewData);
        ImageView imageView = mView.findViewById(R.id.explicit_icon_with_title);
        ImageView imageViewSubtitle = mView.findViewById(R.id.explicit_icon_with_subtitle);
        assertEquals(View.VISIBLE, imageView.getVisibility());
        assertEquals(View.GONE, imageViewSubtitle.getVisibility());
    }

    @Test
    public void onBindNewIndicator() {
        mBrowseViewData = new BrowseViewData(mItems.get(2), LIST_ITEM, mCustomBrowseActions, null);
        mBrowseViewHolder.bind(mContext, mBrowseViewData);
        ImageView newDot = mView.findViewById(R.id.browse_item_progress_new);
        assertEquals(View.VISIBLE, newDot.getVisibility());

        mBrowseViewData = new BrowseViewData(mItems.get(3), LIST_ITEM, mCustomBrowseActions, null);
        mBrowseViewHolder.bind(mContext, mBrowseViewData);
        assertEquals(View.GONE, newDot.getVisibility());
    }

    @Test
    public void onBindProgressUI() {
        mBrowseViewData = new BrowseViewData(mItems.get(2), LIST_ITEM, mCustomBrowseActions, null);
        mBrowseViewHolder.bind(mContext, mBrowseViewData);
        ProgressBar progressBar = mView.findViewById(R.id.browse_item_progress_bar);
        assertEquals(View.VISIBLE, progressBar.getVisibility());
        assertEquals((int) (mItems.get(2).getProgress() * 100), progressBar.getProgress());

        mBrowseViewData = new BrowseViewData(mItems.get(4), LIST_ITEM, mCustomBrowseActions, null);
        mBrowseViewHolder.bind(mContext, mBrowseViewData);
        assertEquals(View.GONE, progressBar.getVisibility());
        assertEquals((int) (mItems.get(4).getProgress() * 100), progressBar.getProgress());
    }

    @Test
    public void updateMediaItemMetaData() {
        mBrowseViewData = new BrowseViewData(mItems.get(2), LIST_ITEM, mCustomBrowseActions, null);
        mBrowseViewHolder.bind(mContext, mBrowseViewData);
        BrowseViewData bvd = new BrowseViewData(mItems.get(3), ICON_LIST_ITEM, mCustomBrowseActions,
                null);
        mBrowseViewHolder.update(bvd, BrowseAdapter.MediaItemUpdateType.PROGRESS);
        ProgressBar progressBar = mView.findViewById(R.id.browse_item_progress_bar);
        ImageView newDot = mView.findViewById(R.id.browse_item_progress_new);
        assertEquals(View.VISIBLE, progressBar.getVisibility());
        assertEquals(View.GONE, newDot.getVisibility());
        assertEquals((int) (mItems.get(3).getProgress() * 100), progressBar.getProgress());
    }
}
