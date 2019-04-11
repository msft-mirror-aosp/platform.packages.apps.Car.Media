package com.android.car.media.widgets;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.transition.Fade;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;

import com.android.car.apps.common.widget.CarTabLayout;
import com.android.car.media.R;
import com.android.car.media.common.MediaAppSelectorWidget;
import com.android.car.media.common.MediaItemMetadata;

import java.util.List;
import java.util.Objects;

/**
 * Media template application bar. The callers should set properties via the public methods (e.g.,
 * {@link setItems()}, {@link setTitle()}, {@link setHasSettings()}), and set the visibility of the
 * views via {@link setState()}. A detailed explanation of all possible states of this application
 * bar can be seen at {@link AppBarView.State}.
 */
public class AppBarView extends ConstraintLayout {
    private static final String TAG = "AppBarView";

    private CarTabLayout<MediaItemTab> mTabsContainer;
    private ImageView mNavIcon;
    private ViewGroup mNavIconContainer;
    private TextView mTitle;
    /** Visible if mHasSettings && mShowSettings. */
    private View mSettingsButton;
    private boolean mHasSettings;
    private boolean mShowSettings;
    private View mSearchButton;
    private EditText mSearchText;
    private MediaAppSelectorWidget mAppSelector;
    private Context mContext;
    private int mMaxTabs;
    private Drawable mArrowBack;
    private Drawable mCollapse;
    private State mState = State.BROWSING;
    private AppBarListener mListener;
    private int mFadeDuration;
    private String mMediaAppTitle;
    private boolean mSearchSupported;
    private boolean mTabsVisible = true;
    private int mHeight;
    private int mFirstRowHeight;
    private int mMaxRows;

    public interface AppBarProvider {
        AppBarView getAppBar();
    }

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
         * Invoked when the user clicks on the collapse button
         */
        void onCollapse();

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
    }

    /**
     * Possible states of this application bar
     */
    public enum State {
        /**
         * Normal application state. If we are able to obtain media items from the media
         * source application, we display them as tabs. Otherwise we show the application name.
         */
        BROWSING,
        /**
         * Indicates that the user has navigated into an element. In this case we show
         * the name of the element and we disable the back button.
         */
        STACKED,
        /**
         * Indicates that we have expanded a view that can be collapsed. We show the
         * title of the application and a collapse icon
         */
        PLAYING,
        /**
         * Indicates that the user is currently entering a search query. We show the search bar and
         * a collapse icon
         */
        SEARCHING,
        /**
         * Used whenever the app bar should not display any information such as when MediaCenter
         * is in an error state
         */
        EMPTY
    }

    public AppBarView(Context context) {
        this(context, null);
    }

    public AppBarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AppBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs, defStyleAttr);
    }

    private void init(Context context, AttributeSet attrs, int defStyleAttr) {
        mMaxTabs = context.getResources().getInteger(R.integer.max_tabs);

        LayoutInflater inflater = (LayoutInflater) context
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.appbar_view, this, true);

        mContext = context;
        mMaxRows = mContext.getResources().getInteger(R.integer.num_app_bar_view_rows);
        mFirstRowHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.appbar_first_row_height);
        mHeight = mFirstRowHeight + mContext.getResources().getDimensionPixelSize(
                R.dimen.appbar_second_row_height);
        if (mMaxRows == 2) {
            ConstraintSet set = new ConstraintSet();
            set.clone(this);
            set.connect(R.id.tabs, ConstraintSet.LEFT, ConstraintSet.PARENT_ID, ConstraintSet.LEFT);
            set.connect(R.id.tabs, ConstraintSet.TOP, R.id.row_separator, ConstraintSet.BOTTOM);
            set.connect(R.id.tabs, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID,
                    ConstraintSet.BOTTOM);
            set.applyTo(this);
        }

        mTabsContainer = findViewById(R.id.tabs);
        mTabsContainer.addOnCarTabSelectedListener(
                new CarTabLayout.SimpleOnCarTabSelectedListener<MediaItemTab>() {
                    @Override
                    public void onCarTabSelected(MediaItemTab mediaItemTab) {
                        if (mListener != null) {
                            mListener.onTabSelected(mediaItemTab.getItem());
                        }
                    }
                });
        mNavIcon = findViewById(R.id.nav_icon);
        mNavIconContainer = findViewById(R.id.nav_icon_container);
        mNavIconContainer.setOnClickListener(view -> onNavIconClicked());
        mAppSelector = findViewById(R.id.app_switch_container);
        mSettingsButton = findViewById(R.id.settings);
        mSettingsButton.setOnClickListener(view -> onSettingsClicked());
        mSearchButton = findViewById(R.id.search);
        mSearchButton.setOnClickListener(view -> onSearchClicked());

        mSearchText = findViewById(R.id.search_bar);
        mSearchText.setOnFocusChangeListener(
                (view, hasFocus) -> {
                    if (hasFocus) {
                        mSearchText.setCursorVisible(true);
                        ((InputMethodManager)
                                context.getSystemService(Context.INPUT_METHOD_SERVICE))
                                .showSoftInput(view, 0);
                    } else {
                        mSearchText.setCursorVisible(false);
                        ((InputMethodManager)
                                context.getSystemService(Context.INPUT_METHOD_SERVICE))
                                .hideSoftInputFromWindow(view.getWindowToken(), 0);
                    }
                });
        mSearchText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                onSearch(editable.toString());
            }
        });
        mSearchText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                mSearchText.setCursorVisible(false);
            }
            return false;
        });

        mTitle = findViewById(R.id.title);
        mArrowBack = getResources().getDrawable(R.drawable.ic_arrow_back, null);
        mCollapse = getResources().getDrawable(R.drawable.ic_expand_more, null);
        mFadeDuration = getResources().getInteger(R.integer.app_selector_fade_duration);
        mMediaAppTitle = getResources().getString(R.string.media_app_title);

        setState(State.BROWSING);
    }

    public void openAppSelector() {
        mAppSelector.open();
    }

    public void closeAppSelector() {
        mAppSelector.close();
    }

    private void onNavIconClicked() {
        if (mListener == null) {
            return;
        }
        switch (mState) {
            case BROWSING:
            case STACKED:
                mListener.onBack();
                break;
            case SEARCHING:
                hideSearchBar();
            case PLAYING:
                mListener.onCollapse();
                break;
        }
    }

    private void onSettingsClicked() {
        if (mListener == null) {
            return;
        }
        mListener.onSettingsSelection();
    }

    private void onSearchClicked() {
        if (mListener == null) {
            return;
        }
        mListener.onSearchSelection();
    }

    private void onSearch(String query) {
        if (mListener == null || TextUtils.isEmpty(query)) {
            return;
        }
        mListener.onSearch(query);
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
        mTabsContainer.clearAllCarTabs();

        if (items != null && !items.isEmpty()) {
            int count = 0;
            for (MediaItemMetadata item : items) {
                MediaItemTab tab = new MediaItemTab(mContext, item);
                mTabsContainer.addCarTab(tab);

                count++;
                if (count >= mMaxTabs) {
                    break;
                }
            }
        }

        // Refresh the views visibility
        setState(mState);
    }

    /**
     * Updates the title to display when the bar is not showing tabs. If the provided title is null,
     * will default to displaying the app name.
     */
    public void setTitle(CharSequence title) {
        mTitle.setText(title != null ? title : mMediaAppTitle);
    }

    /**
     * Sets the name of the currently displayed media app. This is used as the default title for
     * playback and the root browse menu
     */
    public void setMediaAppName(CharSequence appName) {
        mMediaAppTitle = appName == null ? getResources().getString(R.string.media_app_title)
                : appName.toString();
    }

    /** Sets whether the source has settings (not all screens show it). */
    public void setHasSettings(boolean hasSettings) {
        mHasSettings = hasSettings;
        updateSettingsVisibility();
    }

    private void showSettings(boolean showSettings) {
        mShowSettings = showSettings;
        updateSettingsVisibility();
    }

    private void updateSettingsVisibility() {
        mSettingsButton.setVisibility(mHasSettings && mShowSettings ? VISIBLE : GONE);
    }

    /**
     * Updates the currently active item
     */
    public void setActiveItem(MediaItemMetadata item) {
        for (int i = 0; i < mTabsContainer.getCarTabCount(); i++) {
            MediaItemTab mediaItemTab = mTabsContainer.get(i);
            boolean match = item != null && Objects.equals(
                    item.getId(),
                    mediaItemTab.getItem().getId());
            if (match) {
                mTabsContainer.selectCarTab(mediaItemTab);
                return;
            }
        }
    }

    /**
     * Sets whether the search box should be shown
     */
    public void setSearchSupported(boolean supported) {
        mSearchSupported = supported;
        mSearchButton.setVisibility(mSearchSupported ? View.VISIBLE : View.GONE);
    }

    /**
     * Sets whether to show tabs or not. The caller should make sure tabs has at least 1 item before
     * showing tabs.
     */
    private void setShowTabs(boolean visible) {
        // Refresh state to adjust for new tab visibility
        mTabsContainer.setVisibility(visible ? View.VISIBLE : View.GONE);

        // If the app bar has 2 rows, expand to 2 rows if tabs is visible, and collapse to 1
        // row if tabs is invisible.
        if (mMaxRows == 2 && mTabsVisible != visible) {
            ConstraintLayout.LayoutParams layoutParams =
                    (ConstraintLayout.LayoutParams) getLayoutParams();
            if (layoutParams != null) {
                layoutParams.height = visible ? mHeight : mFirstRowHeight;
                setLayoutParams(layoutParams);
            }
        }

        mTabsVisible = visible;
    }

    /**
     * Updates the state of the bar.
     */
    public void setState(State state) {
        mState = state;
        final boolean hasTabs = mTabsContainer.getChildCount() > 0;
        final boolean showTitle = !hasTabs || mMaxRows == 2;

        Transition transition = new Fade().setDuration(mFadeDuration);
        TransitionManager.beginDelayedTransition(this, transition);
        Log.d(TAG, "Updating state: " + state + " (has tabs: " + hasTabs + ")");
        switch (state) {
            case EMPTY:
                mNavIconContainer.setVisibility(View.GONE);
                setShowTabs(false);
                mTitle.setVisibility(View.GONE);
                hideSearchBar();
                showSettings(true);
                break;
            case BROWSING:
                mNavIcon.setImageDrawable(mArrowBack);
                mNavIconContainer.setVisibility(View.GONE);
                setShowTabs(hasTabs);
                mTitle.setVisibility(showTitle ? View.VISIBLE : View.GONE);
                hideSearchBar();
                mSearchButton.setVisibility(mSearchSupported ? View.VISIBLE : View.GONE);
                showSettings(true);
                break;
            case STACKED:
                mNavIcon.setImageDrawable(mArrowBack);
                mNavIconContainer.setVisibility(View.VISIBLE);
                setShowTabs(false);
                mTitle.setVisibility(View.VISIBLE);
                hideSearchBar();
                mSearchButton.setVisibility(View.GONE);
                showSettings(true);
                break;
            case PLAYING:
                break;
            case SEARCHING:
                mNavIcon.setImageDrawable(mCollapse);
                mNavIconContainer.setVisibility(View.VISIBLE);
                setShowTabs(false);
                mTitle.setVisibility(View.GONE);
                mSearchText.setVisibility(View.VISIBLE);
                mSearchText.requestFocus();
                showSettings(true);
                break;
        }
    }

    private void hideSearchBar() {
        mSearchText.setVisibility(View.GONE);
        mSearchText.getText().clear();
    }
}
