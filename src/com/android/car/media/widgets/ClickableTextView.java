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

package com.android.car.media.widgets;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.media.R;

/** TODO(b/260913934) implement links rendering in car-ui-lib. */
@SuppressLint("AppCompatCustomView")
public class ClickableTextView extends TextView {

    public ClickableTextView(@NonNull Context context) {
        super(context);
    }

    public ClickableTextView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ClickableTextView(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setOnClickListener(@Nullable View.OnClickListener l) {
        super.setOnClickListener(l);
        onNewClickListener(l);
    }

    /** Protected so OEMs can extend the view and customize the behavior further. */
    protected void onNewClickListener(@Nullable View.OnClickListener l) {
        int styleId;
        int paintFlags = getPaintFlags();
        if (l != null) {
            styleId = R.style.MetadataPlaybackSubtitleClickableStyle;
            paintFlags |= Paint.UNDERLINE_TEXT_FLAG;
        } else {
            styleId = R.style.MetadataPlaybackSubtitleStyle;
            paintFlags &= ~Paint.UNDERLINE_TEXT_FLAG;
        }

        setTextAppearance(getContext(), styleId);
        setPaintFlags(paintFlags);
    }
}
