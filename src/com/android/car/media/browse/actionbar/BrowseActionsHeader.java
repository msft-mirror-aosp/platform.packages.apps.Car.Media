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

package com.android.car.media.browse.actionbar;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.car.apps.common.imaging.ImageViewBinder;
import com.android.car.media.MediaAppConfig;
import com.android.car.media.R;
import com.android.car.media.common.CustomBrowseAction;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of {@link ActionsHeader}
 * Use this class to show Custom Browse Actions of parent item.
 * Supports either secondary toolbar or recycler view header
 */
public class BrowseActionsHeader extends LinearLayout implements ActionsHeader {
    private ActionClickListener mActionClickListener;
    private OverflowClickListener mOverflowClickListener;
    private List<CustomBrowseAction> mActions = new ArrayList<>();

    public BrowseActionsHeader(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
    }

    @Override
    public void setActionClickedListener(ActionClickListener actionClickListener) {
        mActionClickListener = actionClickListener;
    }

    @Override
    public void setOnOverflowListener(OverflowClickListener overflowListener) {
        mOverflowClickListener = overflowListener;
    }

    @Override
    public void setActions(List<CustomBrowseAction> actions) {
        mActions = actions;
        setHeaderActions(actions);
    }

    private void setHeaderActions(List<CustomBrowseAction> actions) {
        removeAllViews();
        final int maxVisibleActions = getResources()
                .getInteger(R.integer.max_visible_actions_header);
        final Size mMaxArtSize = MediaAppConfig
                .getMediaItemsBitmapMaxSize(getContext());
        actions.stream()
                .limit(maxVisibleActions)
                .collect(Collectors.toList())
                .forEach(
                        action -> {
                            View actionView =
                                    LayoutInflater.from(getContext())
                                            .inflate(R.layout.browse_custom_action, null);
                            ImageView icon =
                                    actionView.findViewById(R.id.browse_item_custom_action);
                            actionView.setOnClickListener(
                                    item -> mActionClickListener.onActionClicked(action));
                            ImageViewBinder<CustomBrowseAction.BrowseActionArtRef> imageBinder =
                                    new ImageViewBinder<>(mMaxArtSize, icon);
                            imageBinder.setImage(getContext(), action.getArtRef());
                            addView(actionView);
                        });

        if (actions.size() > maxVisibleActions) {
            View actionView =
                    LayoutInflater.from(getContext()).inflate(R.layout.browse_custom_action, null);
            ImageView icon = actionView.findViewById(R.id.browse_item_custom_action);
            actionView.setOnClickListener(
                    v ->
                            mOverflowClickListener.onOverFlowCLicked(
                                    actions.subList(maxVisibleActions, actions.size())));
            icon.setImageResource(R.drawable.car_ui_icon_overflow_menu);
            addView(actionView);
        }
    }

    @Override
    public void clearActions() {
        mActions.clear();
        setHeaderActions(mActions);
    }

    @Override
    public void setTitle(CharSequence sourceName) {
        //TODO(b/264473064): Add a text view for title
    }

    @Override
    public void setVisibility(boolean shouldShow) {
        setVisibility(shouldShow ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean isShown() {
        return getVisibility() == View.VISIBLE;
    }
}
