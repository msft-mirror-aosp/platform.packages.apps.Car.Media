/*
 * Copyright 2019 The Android Open Source Project
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
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.car.media.R;

/**
 * This is a compact CarControlBar without controls, just meta data.
 */
public class BrowseMiniMediaItemBar extends ConstraintLayout {

    protected final TextView mTitle;
    protected final TextView mSubtitle;
    protected final ImageView mContentTile;
    protected final ImageView mAppIcon;

    public BrowseMiniMediaItemBar(Context context) {
        this(context, null, 0);
    }

    public BrowseMiniMediaItemBar(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BrowseMiniMediaItemBar(Context context, AttributeSet attrs, int defStyleAttrs) {
        this(context, attrs, defStyleAttrs, R.layout.browse_mini_bar);
    }

    protected BrowseMiniMediaItemBar(Context context, AttributeSet attrs, int defStyleAttrs,
            int layoutId) {
        super(context, attrs, defStyleAttrs);
        inflate(context, layoutId, this);
        mTitle = findViewById(R.id.browse_mini_control_bar_title);
        mSubtitle = findViewById(R.id.browse_mini_control_bar_subtitle);
        mContentTile = findViewById(R.id.browse_mini_control_bar_content_tile);
        mAppIcon = findViewById(R.id.browse_mini_control_bar_app_icon);
    }
}
