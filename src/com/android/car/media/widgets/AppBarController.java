package com.android.car.media.widgets;

import static android.car.media.CarMediaManager.MEDIA_SOURCE_MODE_BROWSE;

import android.app.Application;
import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.XmlRes;
import androidx.core.util.Preconditions;

import com.android.car.media.MediaAppConfig;
import com.android.car.media.R;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.browse.MediaItemsRepository;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.extensions.analytics.event.AnalyticsEvent;
import com.android.car.ui.toolbar.MenuItem;
import com.android.car.ui.toolbar.MenuItemXmlParserUtil;
import com.android.car.ui.toolbar.NavButtonMode;
import com.android.car.ui.toolbar.SearchCapabilities;
import com.android.car.ui.toolbar.SearchConfig;
import com.android.car.ui.toolbar.SearchMode;
import com.android.car.ui.toolbar.ToolbarController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Media template application bar. This class wraps a {@link ToolbarController} and
 * adds media-specific methods to it like {@link #setItems} and {@link #setSearchSupported}.
 */
public class AppBarController {
    private static final int MEDIA_UX_RESTRICTION_DEFAULT =
            CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP;
    private static final int MEDIA_UX_RESTRICTION_NONE = CarUxRestrictions.UX_RESTRICTIONS_BASELINE;

    private final int mMaxTabs;
    private final ArrayList<TabBinder<MediaItemMetadata.ArtworkRef>> mTabs = new ArrayList<>();
    private final ArrayList<String> mPrevTabs = new ArrayList<>();
    private final ToolbarController mToolbarController;
    private final Context mApplicationContext;

    private final boolean mUseSourceLogoForAppSelector;
    // When enabled, tabs will be shown independent of the navigation button.
    private final boolean mShowPersistentTabs;

    private final MenuItem mSearch;
    private final MenuItem mSettings;
    private final MenuItem mEqualizer;
    private final MenuItem mAppSelector;

    @NonNull
    private AppBarListener mListener = new AppBarListener();
    private boolean mSearchSupported;
    private boolean mShowSearchIfSupported;
    private String mSearchQuery;
    private int mSelectedTab = -1;
    private Drawable mLogo;

    /**
     * Application bar listener
     */
    public static class AppBarListener {
        /**
         * Invoked when the user selects an item from the tabs
         */
        protected void onTabSelected(MediaItemMetadata item) {}

        /**
         * Invoked when the user clicks on the settings button.
         */
        protected void onSettingsSelection() {}

        /**
         * Invoked when the user clicks on the equalizer button.
         */
        protected void onEqualizerSelection() {}

        /**
         * Invoked when the user submits a search query.
         */
        protected void onSearch(String query) {}

        /**
         * Invoked when the user clicks on the search button
         */
        protected void onSearchSelection() {}
    }

    /** Returns the maximum number of tabs that can be shown in the bar. */
    public static int getMaxTabs(Context context) {
        return context.getResources().getInteger(R.integer.max_tabs);
    }

    public AppBarController(Context context, ToolbarController controller, @XmlRes int menuResId,
                            boolean useSourceLogoForAppSelector) {
        mToolbarController = controller;
        mApplicationContext = context.getApplicationContext();
        mMaxTabs = getMaxTabs(context);


        mUseSourceLogoForAppSelector = useSourceLogoForAppSelector;
        Intent appSelectorIntent = MediaSource.getSourceSelectorIntent(context, false);

        mShowPersistentTabs = context.getResources().getBoolean(R.bool.show_persistent_tabs);

        mToolbarController.registerSearchListener(query -> {
            mSearchQuery = query;
            mListener.onSearch(query);
        });
        mToolbarController.registerSearchCompletedListener(
                () -> mListener.onSearch(mSearchQuery));

        Map<Integer, MenuItem> menuMap = new HashMap<>();
        List<MenuItem> menuItems = MenuItemXmlParserUtil.readMenuItemList(mApplicationContext,
                menuResId);
        menuItems.forEach((item) -> menuMap.put(item.getId(), item));

        mSearch = menuMap.get(R.id.menu_item_search);
        if (mSearch != null) {
            mSearch.setOnClickListener((menuItem) -> mListener.onSearchSelection());
        }

        mSettings = menuMap.get(R.id.menu_item_setting);
        if (mSettings != null) {
            mSettings.setOnClickListener((menuItem) -> mListener.onSettingsSelection());
        }

        mEqualizer = menuMap.get(R.id.menu_item_equalizer);
        if (mEqualizer != null) {
            mEqualizer.setOnClickListener((menuItem) -> mListener.onEqualizerSelection());
        }

        if (mUseSourceLogoForAppSelector) {
            menuItems.remove(menuMap.get(R.id.menu_item_selector));
            mAppSelector = menuMap.get(R.id.menu_item_selector_with_source_logo);
            // Show the menus while searching so the app logo doesn't disappear.
            mToolbarController.setShowMenuItemsWhileSearching(true);
        } else {
            menuItems.remove(menuMap.get(R.id.menu_item_selector_with_source_logo));
            mAppSelector = menuMap.get(R.id.menu_item_selector);
        }
        if (mAppSelector != null) {
            mAppSelector.setOnClickListener((menuItem) -> context.startActivity(appSelectorIntent));
            mAppSelector.setVisible(appSelectorIntent != null && mAppSelector.isVisible());
        }

        if ((appSelectorIntent != null)
                && context.getResources().getBoolean(R.bool.media_source_logo_opens_app_selector)) {
            mToolbarController.setOnLogoClickListener(
                    () -> context.startActivity(appSelectorIntent));
        }

        mToolbarController.setMenuItems(menuItems);
    }

    /** Verifies that all the menus needed in the browse view have been created. */
    public void checkBrowseMenus() {
        Preconditions.checkNotNull(mSearch);
        Preconditions.checkNotNull(mSettings);
        Preconditions.checkNotNull(mEqualizer);
        Preconditions.checkNotNull(mAppSelector);
    }

    /**
     * Sets a listener of this application bar events. In order to avoid memory leaks, consumers
     * must reset this reference by setting the listener to null.
     */
    public void setListener(@NonNull AppBarListener listener) {
        mListener = listener;
    }

    /**
     * Updates the list of items to show in the application bar tabs.
     *
     * @param items list of tabs to show, or null if no tabs should be shown.
     */
    public void setItems(@Nullable List<MediaItemMetadata> items) {
        if (items == null) {
            items = Collections.emptyList();
            //Tabs are hidden when null.
            MediaItemsRepository.get((Application) mApplicationContext,
                    MEDIA_SOURCE_MODE_BROWSE).getAnalyticsManager().sendVisibleItemsEvents(
                            null, AnalyticsEvent.BROWSE_TABS, AnalyticsEvent.HIDE,
                    AnalyticsEvent.NONE, null);
        }

        for (TabBinder<MediaItemMetadata.ArtworkRef> tabBinder : mTabs) {
            tabBinder.setUpdateListener(null);
            tabBinder.setImage(mApplicationContext, null);
        }

        mTabs.clear();

        Size maxArtSize = MediaAppConfig.getMediaItemsBitmapMaxSize(mApplicationContext);
        for (MediaItemMetadata item : items.subList(0, Math.min(items.size(), mMaxTabs))) {
            TabBinder<MediaItemMetadata.ArtworkRef> newTab = new TabBinder<>(
                    mApplicationContext,
                    maxArtSize,
                    item,
                    item2 -> {
                        mSelectedTab = mTabs.indexOf(item2);
                        mListener.onTabSelected(item2.getMediaItemMetadata());
                    });
            newTab.setImage(mApplicationContext, item.getArtworkKey());
            mTabs.add(newTab);
        }
        mSelectedTab = mTabs.isEmpty() ? -1 : 0;
        for (TabBinder<MediaItemMetadata.ArtworkRef> tabBinder : mTabs) {
            tabBinder.setUpdateListener(x -> updateTabs());
        }
        updateTabs();
    }

    private void updateTabs() {
        if (!mShowPersistentTabs
                && mToolbarController.getNavButtonMode() != NavButtonMode.DISABLED) {
            List<String> currTabs = mTabs.stream().map(
                    (tab) -> tab.getMediaItemMetadata().getId()).collect(Collectors.toList());
            if (!mPrevTabs.isEmpty()) {
                MediaItemsRepository.get((Application) mApplicationContext,
                                MEDIA_SOURCE_MODE_BROWSE)
                        .getAnalyticsManager().sendVisibleItemsEvents(null,
                                AnalyticsEvent.BROWSE_TABS, AnalyticsEvent.HIDE,
                                AnalyticsEvent.NONE, currTabs);
            }
            mPrevTabs.clear();
            mToolbarController.setTabs(Collections.emptyList());
        } else {
            mToolbarController.setTabs(mTabs.stream()
                            .map(TabBinder::getToolbarTab)
                            .collect(Collectors.toList()),
                    mSelectedTab);
            if (mToolbarController.getSearchMode() == SearchMode.DISABLED) {
                List<String> itemsSublist = mTabs
                        .stream()
                        .map(item -> item.getMediaItemMetadata().getId())
                        .collect(Collectors.toCollection(ArrayList::new));

                if (!itemsSublist.equals(mPrevTabs)) {
                    MediaItemsRepository.get((Application) mApplicationContext,
                                    MEDIA_SOURCE_MODE_BROWSE)
                            .getAnalyticsManager().sendVisibleItemsEvents(null,
                                    AnalyticsEvent.BROWSE_TABS, AnalyticsEvent.SHOW,
                                    AnalyticsEvent.NONE, itemsSublist);
                }
                mPrevTabs.clear();
                mPrevTabs.addAll(itemsSublist);
            }
        }
    }

    /** Sets whether the source has settings (not all screens show it). */
    public void setHasSettings(boolean hasSettings) {
        mSettings.setVisible(hasSettings);
    }

    /** Sets whether the source's settings is distraction optimized. */
    public void setSettingsDistractionOptimized(boolean isDistractionOptimized) {
        mSettings.setUxRestrictions(isDistractionOptimized
                ? MEDIA_UX_RESTRICTION_NONE
                : MEDIA_UX_RESTRICTION_DEFAULT);
    }

    /** Sets whether the source has equalizer support. */
    public void setHasEqualizer(boolean hasEqualizer) {
        mEqualizer.setVisible(hasEqualizer);
    }

    /**
     * Sets whether search is supported
     */
    public void setSearchSupported(boolean supported) {
        mSearchSupported = supported;
        updateSearchVisibility();
    }

    /** Sets whether to show the search MenuItem if supported */
    public void showSearchIfSupported(boolean show) {
        mShowSearchIfSupported = show;
        updateSearchVisibility();
    }

    private void updateSearchVisibility() {
        mSearch.setVisible(mShowSearchIfSupported && mSearchSupported);
    }

    /**
     * Updates the currently active item
     */
    public void setActiveItem(MediaItemMetadata item) {
        for (int i = 0; i < mTabs.size(); i++) {
            MediaItemMetadata mediaItemMetadata = mTabs.get(i).getMediaItemMetadata();
            boolean match = item != null && Objects.equals(
                    item.getId(),
                    mediaItemMetadata.getId());
            if (match) {
                mSelectedTab = i;
                // Only select a tab when the tabs are shown.
                if (mToolbarController.getNavButtonMode() == NavButtonMode.DISABLED) {
                    mToolbarController.selectTab(mSelectedTab);
                }
                return;
            }
        }
    }

    public void setSearchQuery(String query) {
        mToolbarController.setSearchQuery(query);
    }

    public void setLogo(Drawable drawable) {
        mLogo = drawable;
        updateLogo();
    }

    private void updateLogo() {
        if (mUseSourceLogoForAppSelector) {
            mAppSelector.setIcon(mLogo);
        } else {
            mToolbarController.setLogo(mLogo);
        }
    }

    public void setSearchIcon(Drawable drawable) {
        mToolbarController.setSearchIcon(drawable);
    }

    public void setTitle(CharSequence title) {
        mToolbarController.setTitle(title);
    }

    public void setTitle(int title) {
        mToolbarController.setTitle(title);
    }

    /** Returns the first menu item matching the given id, or null. */
    public @Nullable MenuItem getMenuItem(int menuId) {
        for (MenuItem menuItem : mToolbarController.getMenuItems()) {
            if (menuItem.getId() == menuId) {
                return menuItem;
            }
        }
        return null;
    }

    /** Sets menu items */
    public void setMenuItems(List<MenuItem> items) {
        mToolbarController.setMenuItems(items);
    }

    public void setBackgroundShown(boolean shown) {
        mToolbarController.setBackgroundShown(shown);
    }

    /** Proxies to {@link ToolbarController#setSearchMode(SearchMode)} */
    public void setSearchMode(SearchMode mode) {
        if (mToolbarController.getSearchMode() != mode) {
            mToolbarController.setSearchMode(mode);
            updateTabs();
            updateLogo();
        }
    }

    /** Proxies to {@link ToolbarController#setNavButtonMode(NavButtonMode)} */
    public void setNavButtonMode(NavButtonMode mode) {
        if (mode != mToolbarController.getNavButtonMode()) {
            mToolbarController.setNavButtonMode(mode);
            updateTabs();
        }
    }

    /** Proxies to {@link ToolbarController#getSearchCapabilities()} */
    public SearchCapabilities getSearchCapabilities() {
        return mToolbarController.getSearchCapabilities();
    }

    /** Proxies to {@link ToolbarController#setSearchConfig(SearchConfig)} */
    public void setSearchConfig(SearchConfig searchConfig) {
        mToolbarController.setSearchConfig(searchConfig);
    }
}
