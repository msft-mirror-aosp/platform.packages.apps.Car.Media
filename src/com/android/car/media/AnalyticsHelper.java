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

import static androidx.recyclerview.widget.RecyclerView.NO_POSITION;

import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.browse.MediaItemsRepository;
import com.android.car.media.extensions.analytics.event.AnalyticsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Helpers for Analytics */
public class AnalyticsHelper {

    /** Creates a sends a scroll event. Returns the items sent. */
    public static List<String> sendScrollEvent(
            MediaItemsRepository repo, MediaItemMetadata parentItem, List<String> prevItems,
            List<MediaItemMetadata> items, int currFirst, int currLast, boolean fromScroll) {

        //Handle empty list by hiding previous and returning empty.
        if (items.isEmpty() && !prevItems.isEmpty()) {
            repo.getAnalyticsManager().sendVisibleItemsEvents(
                    parentItem != null ? parentItem.getId() : null,
                    AnalyticsEvent.BROWSE_LIST, AnalyticsEvent.HIDE,
                    fromScroll ? AnalyticsEvent.SCROLL : AnalyticsEvent.NONE,
                    new ArrayList<>(prevItems));
            return List.of();
        }

        //If for any reason there are no visible items or error state
        // we have nothing to show, hide prev
        if (currFirst == NO_POSITION
                || currLast == NO_POSITION
                || currLast > items.size()
                || items == null) {

            if (!prevItems.isEmpty()) {
                repo.getAnalyticsManager().sendVisibleItemsEvents(
                        parentItem != null ? parentItem.getId() : null,
                        AnalyticsEvent.BROWSE_LIST, AnalyticsEvent.HIDE,
                        fromScroll ? AnalyticsEvent.SCROLL : AnalyticsEvent.NONE,
                        new ArrayList<>(prevItems));
            }

            return List.of();
        }

        //Needed because wide search RV is sometimes given first and last swapped.
        //TODO(b/309150765): remove when fixed.
        int limitedMin = Math.min(currFirst, currLast + 1);
        int limitedMax = Math.max(currFirst, currLast + 1);

        List<String> currItemsSublist = items
                    .subList(limitedMin, Math.min(limitedMax, items.size()))
                    .stream()
                    .map(MediaItemMetadata::getId)
                    .collect(Collectors.toCollection(ArrayList::new));

        List<String> delta = new ArrayList<>(prevItems);
        List<String> deltaNew = new ArrayList<>(currItemsSublist);
        currItemsSublist.forEach(delta::remove);
        prevItems.forEach(deltaNew::remove);

        if (!delta.isEmpty()) {
            repo.getAnalyticsManager().sendVisibleItemsEvents(
                    parentItem != null ? parentItem.getId() : null,
                    AnalyticsEvent.BROWSE_LIST, AnalyticsEvent.HIDE,
                    fromScroll ? AnalyticsEvent.SCROLL : AnalyticsEvent.NONE,
                    new ArrayList<>(delta));
        }
        if (!deltaNew.isEmpty()) {
            repo.getAnalyticsManager().sendVisibleItemsEvents(
                    parentItem != null ? parentItem.getId() : null,
                    AnalyticsEvent.BROWSE_LIST, AnalyticsEvent.SHOW,
                    fromScroll ? AnalyticsEvent.SCROLL : AnalyticsEvent.NONE,
                    new ArrayList<>(deltaNew));
        }

        return currItemsSublist;
    }
}


