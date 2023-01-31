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

import com.android.car.media.common.CustomBrowseAction;

import java.util.List;

/**
 * Custom Browse Actions header.
 * Use {@link BrowseActionsHeader} for implementation.
 */
public interface ActionsHeader {

    /** Custom Browse Action click listener */
    interface ActionClickListener {
        void onActionClicked(CustomBrowseAction action);
    }

    /** Overflow menu click listener */
    interface OverflowClickListener {
        void onOverFlowCLicked(List<CustomBrowseAction> overflowActions);
    }

    /** Sets action clicked listener */
    void setActionClickedListener(ActionClickListener actionClickListener);

    /** Sets overflow menu click listener */
    void setOnOverflowListener(OverflowClickListener overflowListener);

    /** Sets actions list */
    void setActions(List<CustomBrowseAction> actions);

    /** Clears all actions */
    void clearActions();

    /** Sets Actions Header title */
    void setTitle(CharSequence sourceName);

    /** Sets if toolbar is visible*/
    void setVisibility(boolean shouldShow);

    /** Returns whether or not the toolbar is shown */
    boolean isShown();

    /** Returns total height of this view. */
    int getHeight();
}
