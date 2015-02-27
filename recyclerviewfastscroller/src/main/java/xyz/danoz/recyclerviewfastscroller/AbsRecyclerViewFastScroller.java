package xyz.danoz.recyclerviewfastscroller;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.view.ViewCompat;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.OnScrollListener;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.SectionIndexer;

import java.lang.ref.WeakReference;

import xyz.danoz.recyclerviewfastscroller.calculation.progress.ScrollProgressCalculator;
import xyz.danoz.recyclerviewfastscroller.calculation.progress.TouchableScrollProgressCalculator;
import xyz.danoz.recyclerviewfastscroller.sectionindicator.AbsSectionIndicator;
import xyz.danoz.recyclerviewfastscroller.sectionindicator.SectionIndicator;

/**
 * Defines a basic widget that will allow for fast scrolling a RecyclerView using the basic paradigm of
 * a handle and a bar.
 *
 * TODO: More specifics and better support for effectively extending this base class
 */
public abstract class AbsRecyclerViewFastScroller extends RelativeLayout implements RecyclerViewScroller {

    private static final int[] STYLEABLE = R.styleable.rvfs_AbsRecyclerViewFastScroller;

    private static final int AUTO_HIDE_SCROLLER_TIMEOUT_MILLS = 1000;
    private static final float USE_FAST_SCROLLER_THRESHOLD = 3.0f;

    private static final int CURRENT_ANIMATION_NONE = 0;
    private static final int CURRENT_ANIMATION_SHOW = 1;
    private static final int CURRENT_ANIMATION_HIDE = 2;

    /** The container view for the long bar and handle views */
    protected final FrameLayout mBarHandleContainer;
    /** The long bar along which a handle travels */
    protected final View mBar;
    /** The handle that signifies the user's progress in the list */
    protected final View mHandle;

    /* TODO:
     *      Consider making RecyclerView final and should be passed in using a custom attribute
     *      This could allow for some type checking on the section indicator wrt the adapter of the RecyclerView
    */
    private RecyclerView mRecyclerView;
    private SectionIndicator mSectionIndicator;

    /** If I had my druthers, AbsRecyclerViewFastScroller would implement this as an interface, but Android has made
     * {@link OnScrollListener} an abstract class instead of an interface. Hmmm */
    protected OnScrollListener mOnScrollListener;

    /** true: user is grabbing the handle, false: otherwise */
    private boolean mIsGrabbingHandle;

    /** true: always show the scroller, false: hide the scroller automatically */
    private boolean mFastScrollAlwaysVisible = false;
    /** Whether to use fast scroller for the current scroll state */
    private boolean mUsingFastScroller = false;
    /** Type of the view animation (CURRENT_ANIMATION_xxx) */
    private int mCurrentAnimationType = CURRENT_ANIMATION_NONE;
    /** Indicates whether refreshing handle position is required */
    private boolean mForceRefreshHandlePending;
    private Runnable mAutoHideProcessRunnable;
    private Runnable mForceRefreshHandleRunnable;
    private Rect mTempRect = new Rect();
    private InternalAdapterDataObserver mAdapterDataObserver;
    private SectionIndexer mSectionIndexer;

    public AbsRecyclerViewFastScroller(Context context) {
        this(context, null, 0);
    }

    public AbsRecyclerViewFastScroller(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AbsRecyclerViewFastScroller(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        TypedArray attributes = getContext().getTheme().obtainStyledAttributes(attrs, STYLEABLE, 0, 0);

        try {
            int layoutResource = attributes.getResourceId(R.styleable.rvfs_AbsRecyclerViewFastScroller_rvfs_fast_scroller_layout,
                    getLayoutResourceId());
            LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            inflater.inflate(layoutResource, this, true);

            mBarHandleContainer = (FrameLayout) findViewById(R.id.rvfs_scroll_bar_handle_container);
            mBar = findViewById(R.id.rvfs_scroll_bar);
            mHandle = findViewById(R.id.rvfs_scroll_handle);
            mSectionIndicator = (AbsSectionIndicator) findViewById(R.id.rvfs_scroll_section_indicator);

            Drawable barDrawable = attributes.getDrawable(R.styleable.rvfs_AbsRecyclerViewFastScroller_rvfs_barBackground);
            int barColor = attributes.getColor(R.styleable.rvfs_AbsRecyclerViewFastScroller_rvfs_barColor, Color.GRAY);
            applyCustomAttributesToView(mBar, barDrawable, barColor);

            Drawable handleDrawable = attributes.getDrawable(R.styleable.rvfs_AbsRecyclerViewFastScroller_rvfs_handleBackground);
            int handleColor = attributes.getColor(R.styleable.rvfs_AbsRecyclerViewFastScroller_rvfs_handleColor, Color.GRAY);
            applyCustomAttributesToView(mHandle, handleDrawable, handleColor);
        } finally {
            attributes.recycle();
        }

        mBarHandleContainer.setOnTouchListener(new FastScrollerTouchListener(this));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mAutoHideProcessRunnable = new Runnable() {
            @Override
            public void run() {
                hideWithAnimation();
            }
        };
        if (!mFastScrollAlwaysVisible) {
            hide();
        }

        mForceRefreshHandleRunnable = new Runnable() {
            @Override
            public void run() {
                refreshHandle();
            }
        };

        mForceRefreshHandlePending = true;
    }

    protected void refreshHandle() {
        // synchronize the handle position to the RecyclerView
        if (getScrollProgressCalculator() != null && mRecyclerView != null) {
            float scrollProgress = getScrollProgressCalculator().calculateScrollProgress(mRecyclerView);
            moveHandleToPosition(scrollProgress);
        }
    }

    protected void postRefreshHandle() {
        if (mForceRefreshHandleRunnable != null) {
            removeCallbacks(mForceRefreshHandleRunnable);
            post(mForceRefreshHandleRunnable);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        removeCallbacks(mForceRefreshHandleRunnable);
        mForceRefreshHandleRunnable = null;

        cancelAutoHideScrollerProcess();
        mAutoHideProcessRunnable = null;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mForceRefreshHandlePending = true;
    }

    private void applyCustomAttributesToView(View view, Drawable drawable, int color) {
        if (drawable != null) {
            setViewBackground(view, drawable);
        } else {
            view.setBackgroundColor(color);
        }
    }

    /**
     * Provides the ability to programmatically set the color of the fast scroller's handle
     * @param color for the handle to be
     */
    public void setHandleColor(int color) {
        mHandle.setBackgroundColor(color);
    }

    /**
     * Provides the ability to programmatically set the background drawable of the fast scroller's handle
     * @param drawable for the handle's background
     */
    public void setHandleBackground(Drawable drawable) {
        setViewBackground(mHandle, drawable);
    }

    /**
     * Provides the ability to programmatically set the color of the fast scroller's bar
     * @param color for the bar to be
     */
    public void setBarColor(int color) {
        mBar.setBackgroundColor(color);
    }

    /**
     * Provides the ability to programmatically set the background drawable of the fast scroller's bar
     * @param drawable for the bar's background
     */
    public void setBarBackground(Drawable drawable) {
        setViewBackground(mBar, drawable);
    }

    private void setViewBackground(View view, Drawable background) {
        if (VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN) {
            view.setBackground(background);
        } else {
            //noinspection deprecation
            view.setBackgroundDrawable(background);
        }
    }

    @Override
    public void setRecyclerView(RecyclerView recyclerView) {
        mRecyclerView = recyclerView;

        if (mRecyclerView != null) {
            setStandardScrollerEnabled(mRecyclerView, false);
        }
    }

    @Nullable
    public SectionIndicator getSectionIndicator() {
        return mSectionIndicator;
    }

    /**
     * Sets {@link android.widget.SectionIndexer}
     * @param sectionIndexer section indexer
     */
    public void setSectionIndexer(SectionIndexer sectionIndexer) {
        mSectionIndexer = sectionIndexer;
    }

    /**
     * Gets {@link android.widget.SectionIndexer}.
     * @return section indexer
     */
    @Nullable
    public SectionIndexer getSectionIndexer() {
        if (mSectionIndexer != null) {
            return mSectionIndexer;
        }

        // check whether the adapter implements indexer
        final RecyclerView.Adapter adapter = (mRecyclerView != null) ? mRecyclerView.getAdapter() : null;

        if (adapter instanceof SectionIndexer) {
            return (SectionIndexer) adapter;
        } else {
            return null;
        }
    }

    @Override
    public void scrollTo(float scrollProgress, boolean fromTouch) {
        int position = scrollToProgress(mRecyclerView, scrollProgress);
        updateSectionIndicator(position, scrollProgress);
    }

    private void updateSectionIndicator(int position, float scrollProgress) {
        if (mSectionIndicator != null) {
            mSectionIndicator.setProgress(scrollProgress);

            SectionIndexer indexer = getSectionIndexer();
            if (indexer != null) {
                int section = indexer.getSectionForPosition(position);
                Object[] sections = indexer.getSections();
                mSectionIndicator.setSection(sections[section]);
            }
        }
    }

    /**
     * Classes that extend AbsFastScroller must implement their own {@link OnScrollListener} to respond to scroll
     * events when the {@link #mRecyclerView} is scrolled NOT using the fast scroller.
     * @return an implementation for responding to scroll events from the {@link #mRecyclerView}
     */
    @NonNull
    public OnScrollListener getOnScrollListener() {
        if (mOnScrollListener == null) {
            mOnScrollListener = new OnScrollListener() {
                @Override
                public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                    handleOnScrollStateChanged(recyclerView, newState);
                }

                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    handleOnScrolled(recyclerView, dx, dy);
                }
            };
        }
        return mOnScrollListener;
    }

    /**
     * Returns {@link android.support.v7.widget.RecyclerView.AdapterDataObserver} object which observes
     * data set changes and automatically refresh the scroller's handle position.
     *
     * @return observer object
     */
    public RecyclerView.AdapterDataObserver getAdapterDataObserver() {
        if (mAdapterDataObserver == null) {
            mAdapterDataObserver = new InternalAdapterDataObserver(this);
        }
        return mAdapterDataObserver;
    }

    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        postRefreshHandle();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if (changed) {
            final Rect bounds = mTempRect;

            bounds.left = mBar.getLeft();
            bounds.top = mBar.getTop();
            bounds.right = mBar.getRight();
            bounds.bottom = mBar.getBottom();

            onUpdateScrollBarBounds(bounds);

            if (mSectionIndicator != null) {
                mSectionIndicator.onUpdateScrollBarBounds(bounds);
            }
        }

        // refresh handle position if needed
        if (mForceRefreshHandlePending) {
            mForceRefreshHandlePending = false;
            post(mForceRefreshHandleRunnable);
        }
    }

    private void handleOnScrollStateChanged(RecyclerView recyclerView, int newState) {
        switch (newState) {
            case RecyclerView.SCROLL_STATE_IDLE:
                mUsingFastScroller = false;
                break;
            case RecyclerView.SCROLL_STATE_DRAGGING:
            case RecyclerView.SCROLL_STATE_SETTLING:
                if (mFastScrollAlwaysVisible) {
                    mUsingFastScroller = true;
                    setStandardScrollerEnabled(recyclerView, false);
                } else {
                    final int numItemsPerPage = getNumItemsPerPage(recyclerView);
                    final int numTotalItems = recyclerView.getAdapter().getItemCount();
                    final float numPages = (numItemsPerPage > 0) ? (float) numTotalItems / numItemsPerPage : 0;

                    mUsingFastScroller = (numPages >= USE_FAST_SCROLLER_THRESHOLD);

                    setStandardScrollerEnabled(recyclerView, !mUsingFastScroller);
                }
                break;
        }
    }

    private void handleOnScrolled(RecyclerView recyclerView, int dx, int dy) {
        // update handle position
        if (!mIsGrabbingHandle) {
            refreshHandle();
        }

        // show scroll bar
        if (!mFastScrollAlwaysVisible && mUsingFastScroller) {
            showWithAnimation();
            if (!mIsGrabbingHandle) {
                scheduleAutoHideScrollerProcess();
            }
        }
    }

    /**
     * Sub classes have to override this method and prepare for scrolling.
     */
    protected abstract void onUpdateScrollBarBounds(Rect barBounds);

    /**
     * Returns true if the fast scroller is set to always show on this view.
     *
     * @return true if the fast scroller will always show
     */
    public boolean isFastScrollAlwaysVisible() {
        return mFastScrollAlwaysVisible;
    }

    /**
     * Sets whether or not the fast scroller should always be shown.
     *
     * @param alwaysVisible true if the fast scroller should always be displayed, false otherwise
     */
    public void setFastScrollAlwaysVisible(boolean alwaysVisible) {
        if (mFastScrollAlwaysVisible == alwaysVisible) {
            return;
        }
        mFastScrollAlwaysVisible = alwaysVisible;
        if (mFastScrollAlwaysVisible) {
            show();
        } else {
            cancelAutoHideScrollerProcess();
        }
    }

    private void scheduleAutoHideScrollerProcess() {
        cancelAutoHideScrollerProcess();

        if (!mFastScrollAlwaysVisible) {
            postDelayed(mAutoHideProcessRunnable, AUTO_HIDE_SCROLLER_TIMEOUT_MILLS);
        }
    }

    private void cancelAutoHideScrollerProcess() {
        removeCallbacks(mAutoHideProcessRunnable);
    }

    private void show() {
        cancelAutoHideScrollerProcess();

        if (getAnimation() != null) {
            getAnimation().cancel();
        }

        ViewCompat.setTranslationX(this, 0);
        ViewCompat.setTranslationY(this, 0);

        setVisibility(View.VISIBLE);
    }

    private void hide() {
        cancelAutoHideScrollerProcess();

        if (getAnimation() != null) {
            getAnimation().cancel();
        }
        setVisibility(View.INVISIBLE);
    }

    private void showWithAnimation() {
        if ((mCurrentAnimationType == CURRENT_ANIMATION_SHOW) || (getVisibility() == View.VISIBLE)) {
            return;
        }

        cancelAutoHideScrollerProcess();

        if (getAnimation() != null) {
            getAnimation().cancel();
        }

        final Animation anim = loadShowAnimation();

        if (anim != null) {
            anim.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {
                    setVisibility(View.VISIBLE);
                }

                @Override
                public void onAnimationEnd(Animation animation) {
                    mCurrentAnimationType = CURRENT_ANIMATION_NONE;
                }

                @Override
                public void onAnimationRepeat(Animation animation) {
                }
            });

            mCurrentAnimationType = CURRENT_ANIMATION_SHOW;

            startAnimation(anim);
        } else {
            show();
        }
    }

    private void hideWithAnimation() {
        if ((mCurrentAnimationType == CURRENT_ANIMATION_HIDE) || (getVisibility() != View.VISIBLE)) {
            return;
        }

        cancelAutoHideScrollerProcess();

        if (getAnimation() != null) {
            getAnimation().cancel();
        }

        final Animation anim = loadHideAnimation();

        if (anim != null) {
            anim.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {
                }

                @Override
                public void onAnimationEnd(Animation animation) {
                    mCurrentAnimationType = CURRENT_ANIMATION_NONE;
                    setVisibility(View.INVISIBLE);
                }

                @Override
                public void onAnimationRepeat(Animation animation) {
                }
            });

            mCurrentAnimationType = CURRENT_ANIMATION_HIDE;

            startAnimation(anim);
        } else {
            hide();
        }
    }

    /**
     * Sets whether user is grabbing the scroller handle
     * @param isGrabbingHandle true: grabbing, false: not grabbing
     */
    public void setIsGrabbingHandle(boolean isGrabbingHandle) {
        if (mIsGrabbingHandle == isGrabbingHandle) {
            return;
        }

        mIsGrabbingHandle = isGrabbingHandle;

        if (!mFastScrollAlwaysVisible) {
            if (isGrabbingHandle) {
                show();
            } else {
                scheduleAutoHideScrollerProcess();
            }
        }
    }

    /**
     * Takes a touch event and determines how much scroll progress this translates into
     * @param event touch event received by the layout
     * @return scroll progress, or fraction by which list is scrolled [0 to 1]
     */
    public float getScrollProgress(MotionEvent event) {
        return getScrollProgressCalculator().calculateScrollProgress(event);
    }

    /**
     * Define a layout resource for your implementation of AbsFastScroller
     * Currently must contain a handle view (R.id.scroll_handle) and a bar (R.id.scroll_bar)
     * @return a resource id corresponding to the chosen layout.
     */
    protected abstract int getLayoutResourceId();

    /**
     * Define a ScrollProgressCalculator for your implementation of AbsFastScroller
     * @return a chosen implementation of {@link ScrollProgressCalculator}
     */
    protected abstract TouchableScrollProgressCalculator getScrollProgressCalculator();

    /**
     * Moves the handle of the scroller by specific progress amount
     * @param scrollProgress fraction by which to move scroller [0 to 1]
     */
    public abstract void moveHandleToPosition(float scrollProgress);

    /**
     * Gets how many items can be displayed in a single page
     * @param recyclerView {@link android.support.v7.widget.RecyclerView} instance which is associated to the scroller
     * @return number of items
     */
    public abstract int getNumItemsPerPage(RecyclerView recyclerView);

    /**
     * Sets whether to use standard scroller
     * @param recyclerView {@link android.support.v7.widget.RecyclerView} instance which is associated to the scroller
     * @param enabled whether to use standard scroller
     */
    protected abstract void setStandardScrollerEnabled(RecyclerView recyclerView, boolean enabled);

    /**
     * Loads scroller show animation
     * @return animation which is used for the showWithAnimation() method
     */
    protected abstract Animation loadShowAnimation();

    /**
     * Loads scroller hide animation
     * @return animation which is used for the hideWithAnimation() method
     */
    protected abstract Animation loadHideAnimation();

    /**
     * Scroll {@link android.support.v7.widget.RecyclerView} to specified scroller progress
     * @param recyclerView Target {@link android.support.v7.widget.RecyclerView} instance
     * @param progress Scroll progress [0.0 .. 1.0]
     *
     * @return scrolled position
     */
    protected abstract int scrollToProgress(RecyclerView recyclerView, float progress);


    private static class InternalAdapterDataObserver extends RecyclerView.AdapterDataObserver {
        private WeakReference<AbsRecyclerViewFastScroller> mRefScroller;

        public InternalAdapterDataObserver(AbsRecyclerViewFastScroller scroller) {
            super();
            mRefScroller = new WeakReference<>(scroller);
        }

        private void onAdapterDataChanged() {
            final AbsRecyclerViewFastScroller scroller = mRefScroller.get();
            if (scroller != null) {
                scroller.postRefreshHandle();
            }
        }

        @Override
        public void onChanged() {
            onAdapterDataChanged();
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount) {
            onAdapterDataChanged();
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            onAdapterDataChanged();
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            onAdapterDataChanged();
        }

        @Override
        public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
            onAdapterDataChanged();
        }
    }
}