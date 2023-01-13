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

import android.content.Context;
import android.view.View;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.media.utils.MediaConstants;

import com.android.car.apps.common.util.ViewUtils;
import com.android.car.media.common.CustomBrowseAction;
import com.android.car.media.common.MediaItemMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Utility class for {@link BrowseViewHolder}
 */
public class BrowseAdapterUtils {

    /**
     * <p>
     * Handles hiding and showing new media indicator.
     * </p>
     *
     * <p>
     * {@link MediaConstants#DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED}
     * completeIndicator visible
     * </p>
     * <p>
     * {@link MediaConstants#DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED}
     * completeIndicator hidden
     * </p>
     * <p>
     * {@link MediaConstants#DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED}
     * completeIndicator hidden
     * </p>
     * Default: Hidden
     *
     * @param status            as defined {@link MediaConstants}
     *                          with key
     *                          {@link MediaConstants#DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS}
     * @param completeIndicator view for complete indicator
     */
    public static void handleNewMediaIndicator(int status, View completeIndicator) {
        if (completeIndicator != null) {
            ViewUtils.setVisible(completeIndicator,
                    status == MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED);
        }
    }

    /**
     * Sets progressbar progress.
     * Converts the progress param from 0.0 - 1.0 to 0 - 100
     * @param progressIndicator - Progressbar
     * @param progress - 0.0 - 1.0
     */
    public static void setPlaybackProgressIndicator(ProgressBar progressIndicator,
            double progress) {
        if (progressIndicator != null) {
            ViewUtils.setVisible(progressIndicator, progress > 0.0 && progress < 1.0);
            progressIndicator.setProgress((int) (progress * 100));
        }
    }

    /**
     * Builds list of {@link CustomBrowseAction} from the supplied media item.
     *
     * @param item - contains the list of action IDs
     * @param globalBrowseCustomActions - Global actions in root.extras
     * @return list of actions for item
     */
    public static List<CustomBrowseAction> buildBrowseCustomActions(
            Context mContext,
            MediaItemMetadata item,
            @NonNull Map<String, CustomBrowseAction> globalBrowseCustomActions) {
        int actionsLimit =  mContext.getResources()
                .getInteger(com.android.car.media.common.R.integer.max_custom_actions);
        if (actionsLimit <= 0) return new ArrayList<>();
        if (globalBrowseCustomActions.isEmpty()) return new ArrayList<>();

        List<CustomBrowseAction> customActions = new ArrayList<>();

        for (String actionId : item.getBrowseCustomActionIds()) {
            if (globalBrowseCustomActions.containsKey(actionId)) {
                CustomBrowseAction customBrowseAction = globalBrowseCustomActions.get(actionId);
                if (customBrowseAction == null) continue;
                customActions.add(customBrowseAction);
            }
        }

        //Limit item actions to OEM set value
        actionsLimit = Math.min(customActions.size(), actionsLimit);

        return customActions.subList(0, actionsLimit);
    }
}
