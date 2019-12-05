package com.android.car.media.widgets;

import android.annotation.Nullable;
import android.annotation.NonNull;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.android.car.media.R;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.ui.toolbar.MenuItem;
import com.android.car.ui.toolbar.TabLayout;
import com.android.car.ui.toolbar.Toolbar;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Media template application bar. The callers should set properties via the public methods (e.g.,
 * {@link #setItems}, {@link #setTitle}, {@link #setHasSettings}), and set the visibility of the
 * views via {@link #setState}. A detailed explanation of all possible states of this application
 * bar can be seen at {@link Toolbar.State}.
 */
public class AppBarView extends Toolbar {
    private int mMaxTabs;

    @NonNull
    private AppBarListener mListener = DEFAULT_APPBAR_LISTENER;
    private MenuItem mSearch;
    private MenuItem mSettings;
    private MenuItem mAppSelector;

    /**
     * Application bar listener
     */
    public interface AppBarListener {
        /**
         * Invoked when the user selects an item from the tabs
         */
        void onTabSelected(MediaItemMetadata item);

        /**
         * Invoked when the user clicks on the back button
         */
        void onBack();

        /**
         * Invoked when the user clicks on the settings button.
         */
        void onSettingsSelection();

        /**
         * Invoked when the user submits a search query.
         */
        void onSearch(String query);

        /**
         * Invoked when the user clicks on the search button
         */
        void onSearchSelection();

        /**
         * Invoked when the user clicks on the app switch button
         */
        void onAppSwitch();

        /**
         * Invoked when the height of the toolbar changes
         */
        void onHeightChanged(int height);
    }

    private static final AppBarListener DEFAULT_APPBAR_LISTENER = new AppBarListener() {
        @Override
        public void onTabSelected(MediaItemMetadata item) {}

        @Override
        public void onBack() {}

        @Override
        public void onSettingsSelection() {}

        @Override
        public void onSearch(String query) {}

        @Override
        public void onSearchSelection() {}

        @Override
        public void onAppSwitch() {}

        @Override
        public void onHeightChanged(int height) {}
    };

    public AppBarView(Context context) {
        this(context, null);
    }

    public AppBarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AppBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        mMaxTabs = context.getResources().getInteger(R.integer.max_tabs);

        registerOnTabSelectedListener(tab ->
                mListener.onTabSelected(((MediaItemTab) tab).getItem()));
        registerOnBackListener(() -> {
            mListener.onBack();
            return true;
        });
        registerOnSearchListener(query -> mListener.onSearch(query));
        registerToolbarHeightChangeListener(height -> mListener.onHeightChanged(height));
        mSearch = MenuItem.Builder.createSearch(context, v -> mListener.onSearchSelection());
        mSettings = MenuItem.Builder.createSettings(context, v -> mListener.onSettingsSelection());
        mAppSelector = new MenuItem.Builder(context)
                .setIcon(R.drawable.ic_app_switch)
                .setOnClickListener(m -> mListener.onAppSwitch())
                .build();
        setMenuItems(Arrays.asList(mSearch, mSettings, mAppSelector));
    }

    /**
     * Sets a listener of this application bar events. In order to avoid memory leaks, consumers
     * must reset this reference by setting the listener to null.
     */
    public void setListener(AppBarListener listener) {
        mListener = listener;
    }

    /**
     * Updates the list of items to show in the application bar tabs.
     *
     * @param items list of tabs to show, or null if no tabs should be shown.
     */
    public void setItems(@Nullable List<MediaItemMetadata> items) {
        clearAllTabs();

        if (items != null && !items.isEmpty()) {
            int count = 0;
            for (MediaItemMetadata item : items) {
                addTab(new MediaItemTab(item));

                count++;
                if (count >= mMaxTabs) {
                    break;
                }
            }
        }
    }

    /** Sets whether the source has settings (not all screens show it). */
    public void setHasSettings(boolean hasSettings) {
        mSettings.setVisible(hasSettings);
    }

    /**
     * Sets whether the search box should be shown
     */
    public void setSearchSupported(boolean supported) {
        mSearch.setVisible(supported);
    }

    /**
     * Sets whether launching app selector is supported
     */
    public void setAppLauncherSupported(boolean supported) {
        mAppSelector.setVisible(supported);
    }

    /**
     * Updates the currently active item
     */
    public void setActiveItem(MediaItemMetadata item) {
        for (int i = 0; i < getTabLayout().getTabCount(); i++) {
            MediaItemTab mediaItemTab = (MediaItemTab) getTabLayout().get(i);
            boolean match = item != null && Objects.equals(
                    item.getId(),
                    mediaItemTab.getItem().getId());
            if (match) {
                getTabLayout().selectTab(i);
                return;
            }
        }
    }
}
