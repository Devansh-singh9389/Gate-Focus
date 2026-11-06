package com.gatefocus.app.views

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import com.gatefocus.app.R
import kotlin.math.sin

/**
 * Modern Telegram-style floating capsule navigation bar.
 * Features:
 * - Fluid sliding indicator pill with elastic stretch physics
 * - Micro-interaction bouncy scale on icon tap
 * - Active/inactive color transitions
 * - Real-time ViewPager2 scroll synchronization
 * - Haptic feedback on tab selection
 */
class TelegramCapsuleNavBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    data class TabItem(
        val id: Int,
        val iconRes: Int,
        val titleRes: Int
    )

    private val tabs = listOf(
        TabItem(R.id.nav_focus, R.drawable.ic_timer, R.string.tab_focus),
        TabItem(R.id.nav_tracker, R.drawable.ic_checklist, R.string.tab_tracker),
        TabItem(R.id.nav_practice, R.drawable.ic_quiz, R.string.tab_practice),
        TabItem(R.id.nav_library, R.drawable.ic_library, R.string.tab_library),
        TabItem(R.id.nav_settings, R.drawable.ic_settings, R.string.tab_settings)
    )

    interface OnTabSelectedListener {
        fun onTabSelected(position: Int, tabId: Int)
    }

    private var listener: OnTabSelectedListener? = null
    var selectedIndex: Int = 0
        private set

    var selectedItemId: Int
        get() = tabs.getOrNull(selectedIndex)?.id ?: tabs.first().id
        set(value) {
            selectTabById(value, animate = true, notify = true)
        }

    private val capsuleContainer: FrameLayout
    private val indicatorView: View
    private val tabItemsContainer: LinearLayout
    private val tabViews = mutableListOf<TabViewHolders>()

    private var indicatorAnimator: ValueAnimator? = null
    private var isTabDriven = false
    private var scrollState = 0
    private val argbEvaluator = ArgbEvaluator()

    // Colors
    private val colorActiveIcon = ContextCompat.getColor(context, R.color.nav_active)
    private val colorInactiveIcon = ContextCompat.getColor(context, R.color.nav_inactive)
    private val colorActiveText = ContextCompat.getColor(context, R.color.nav_selected_text)
    private val colorInactiveText = ContextCompat.getColor(context, R.color.nav_inactive)

    private data class TabViewHolders(
        val root: LinearLayout,
        val icon: ImageView,
        val text: TextView,
        val tabItem: TabItem
    )

    init {
        // Capsule Container with sleek dark background and subtle border
        capsuleContainer = FrameLayout(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_telegram_capsule_nav)
            elevation = dpToPx(12f)
            clipToPadding = false
        }

        val capsuleParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            dpToPx(62f).toInt()
        ).apply {
            gravity = Gravity.CENTER
        }
        addView(capsuleContainer, capsuleParams)

        // Sliding Indicator Pill
        indicatorView = View(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_telegram_indicator)
            alpha = 0.95f
        }
        val indicatorParams = LayoutParams(
            0,
            dpToPx(48f).toInt()
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        capsuleContainer.addView(indicatorView, indicatorParams)

        // Tab Items Container
        tabItemsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = tabs.size.toFloat()
        }
        capsuleContainer.addView(
            tabItemsContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        buildTabs()
    }

    private fun buildTabs() {
        tabViews.clear()
        tabItemsContainer.removeAllViews()

        tabs.forEachIndexed { index, tabItem ->
            val itemView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onTabClicked(index)
                }
            }

            val iconView = ImageView(context).apply {
                setImageResource(tabItem.iconRes)
                imageTintList = ColorStateList.valueOf(
                    if (index == selectedIndex) colorActiveIcon else colorInactiveIcon
                )
            }
            val iconParams = LinearLayout.LayoutParams(
                dpToPx(22f).toInt(),
                dpToPx(22f).toInt()
            )
            itemView.addView(iconView, iconParams)

            val textView = TextView(context).apply {
                setText(tabItem.titleRes)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(if (index == selectedIndex) colorActiveText else colorInactiveText)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val textParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(2f).toInt()
            }
            itemView.addView(textView, textParams)

            val itemParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
            tabItemsContainer.addView(itemView, itemParams)
            tabViews.add(TabViewHolders(itemView, iconView, textView, tabItem))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post {
            setupIndicatorSizeAndPosition()
        }
    }

    private fun setupIndicatorSizeAndPosition() {
        if (tabs.isEmpty() || tabItemsContainer.width == 0) return
        val tabWidth = tabItemsContainer.width.toFloat() / tabs.size
        val indicatorPadding = dpToPx(6f)
        val indicatorWidth = tabWidth - (indicatorPadding * 2)

        val params = indicatorView.layoutParams as LayoutParams
        params.width = indicatorWidth.toInt()
        indicatorView.layoutParams = params

        val targetX = (selectedIndex * tabWidth) + indicatorPadding
        indicatorView.translationX = targetX
        updateTabStyles(selectedIndex)
    }

    private fun onTabClicked(index: Int) {
        if (index == selectedIndex) return

        // Light haptic feedback
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        selectTab(index, animate = true, notify = true)
    }

    fun setOnTabSelectedListener(listener: OnTabSelectedListener) {
        this.listener = listener
    }

    fun selectTab(index: Int, animate: Boolean = true, notify: Boolean = true) {
        if (index < 0 || index >= tabs.size) return
        val prevIndex = selectedIndex
        selectedIndex = index
        isTabDriven = true

        if (animate && tabItemsContainer.width > 0) {
            animateIndicatorTo(prevIndex, index)
            animateTabBounce(index)
        } else {
            indicatorAnimator?.cancel()
            val tabWidth = tabItemsContainer.width.toFloat() / tabs.size
            val indicatorPadding = dpToPx(6f)
            indicatorView.translationX = (index * tabWidth) + indicatorPadding
            updateTabStyles(index)
        }

        if (notify) {
            listener?.onTabSelected(index, tabs[index].id)
        }
    }

    fun selectTabById(tabId: Int, animate: Boolean = true, notify: Boolean = true) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index != -1) {
            selectTab(index, animate, notify)
        }
    }

    /**
     * Animate the sliding indicator pill with elastic fluid stretch physics
     */
    private fun animateIndicatorTo(fromIndex: Int, toIndex: Int) {
        val tabWidth = tabItemsContainer.width.toFloat() / tabs.size
        val indicatorPadding = dpToPx(6f)
        val startX = indicatorView.translationX
        val targetX = (toIndex * tabWidth) + indicatorPadding
        val travelDistance = targetX - startX

        indicatorAnimator?.cancel()
        indicatorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300L
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { va ->
                val fraction = va.animatedValue as Float
                indicatorView.translationX = startX + travelDistance * fraction

                // Liquid elastic pill stretch mid-transit
                val stretch = 1f + 0.18f * sin(fraction * Math.PI.toFloat())
                indicatorView.scaleX = stretch

                // Crossfade colors smoothly
                blendTabColors(fromIndex, toIndex, fraction)
            }
            doOnEnd {
                indicatorView.scaleX = 1f
                indicatorView.translationX = targetX
                updateTabStyles(toIndex)
            }
            start()
        }
    }

    /**
     * Bouncy micro-interaction on the selected tab icon
     */
    private fun animateTabBounce(index: Int) {
        val holder = tabViews.getOrNull(index) ?: return
        val icon = holder.icon

        icon.animate().cancel()
        icon.scaleX = 1.0f
        icon.scaleY = 1.0f

        // Step 1: Scale up with ease
        icon.animate()
            .scaleX(1.22f)
            .scaleY(1.22f)
            .setDuration(130L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                // Step 2: Spring back with subtle overshoot
                icon.animate()
                    .scaleX(1.05f)
                    .scaleY(1.05f)
                    .setDuration(170L)
                    .setInterpolator(OvershootInterpolator(2.5f))
                    .start()
            }
            .start()

        // Settle other tab icons
        tabViews.forEachIndexed { i, otherHolder ->
            if (i != index) {
                otherHolder.icon.animate().cancel()
                otherHolder.icon.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(160L)
                    .start()
            }
        }
    }

    private fun blendTabColors(fromIndex: Int, toIndex: Int, fraction: Float) {
        if (fromIndex in tabViews.indices) {
            val fromHolder = tabViews[fromIndex]
            val iconColor = argbEvaluator.evaluate(fraction, colorActiveIcon, colorInactiveIcon) as Int
            val textColor = argbEvaluator.evaluate(fraction, colorActiveText, colorInactiveText) as Int
            fromHolder.icon.imageTintList = ColorStateList.valueOf(iconColor)
            fromHolder.text.setTextColor(textColor)
        }

        if (toIndex in tabViews.indices) {
            val toHolder = tabViews[toIndex]
            val iconColor = argbEvaluator.evaluate(fraction, colorInactiveIcon, colorActiveIcon) as Int
            val textColor = argbEvaluator.evaluate(fraction, colorInactiveText, colorActiveText) as Int
            toHolder.icon.imageTintList = ColorStateList.valueOf(iconColor)
            toHolder.text.setTextColor(textColor)
        }
    }

    private fun updateTabStyles(activeIdx: Int) {
        tabViews.forEachIndexed { index, holder ->
            val isActive = index == activeIdx
            holder.icon.imageTintList = ColorStateList.valueOf(
                if (isActive) colorActiveIcon else colorInactiveIcon
            )
            holder.text.setTextColor(if (isActive) colorActiveText else colorInactiveText)
            if (!isActive) {
                holder.icon.scaleX = 1.0f
                holder.icon.scaleY = 1.0f
            }
        }
    }

    /**
     * Track ViewPager2 scroll state to distinguish user drags from programmatic scrolls
     */
    fun onPageScrollStateChanged(state: Int) {
        scrollState = state
        if (state == 1) { // SCROLL_STATE_DRAGGING — user finger swipe
            isTabDriven = false
            indicatorAnimator?.cancel()
        }
        if (state == 0) { // SCROLL_STATE_IDLE — all scrolling done
            isTabDriven = false
        }
    }

    /**
     * Track ViewPager2 drag scroll in real-time
     */
    fun onPageScrolled(position: Int, positionOffset: Float) {
        if (isTabDriven) return
        if (tabItemsContainer.width == 0 || tabs.isEmpty()) return

        val tabWidth = tabItemsContainer.width.toFloat() / tabs.size
        val indicatorPadding = dpToPx(6f)
        val currentX = ((position + positionOffset) * tabWidth) + indicatorPadding
        indicatorView.translationX = currentX

        val nextPosition = (position + 1).coerceAtMost(tabs.size - 1)
        blendTabColors(position, nextPosition, positionOffset)
    }

    fun onPageSelected(position: Int) {
        if (isTabDriven) {
            selectedIndex = position
            return
        }
        if (position != selectedIndex) {
            selectedIndex = position
            updateTabStyles(position)
            animateTabBounce(position)
            val tabWidth = tabItemsContainer.width.toFloat() / tabs.size
            val indicatorPadding = dpToPx(6f)
            indicatorView.translationX = (position * tabWidth) + indicatorPadding
        }
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        )
    }
}
