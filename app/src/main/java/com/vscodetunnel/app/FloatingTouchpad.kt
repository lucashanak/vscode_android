package com.vscodetunnel.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class FloatingTouchpad(
    context: Context,
    private val overlayManager: OverlayManager
) : FrameLayout(context) {

    companion object {
        private const val TAP_MOVE_THRESHOLD = 10f // dp
        private const val TAP_TIMEOUT = 300L
        private const val DOUBLE_TAP_TIMEOUT = 300L
        private const val EDGE_SCROLL_FRACTION = 0.15f // rightmost 15% = scroll zone
    }

    // Touch state
    private var tracking = false
    private var lastX = 0f
    private var lastY = 0f
    private var totalMove = 0f
    private var fingerCount = 0
    private var scrollLastY = 0f
    private var tapStart = 0L
    private var lastTapTime = 0L
    private var isDragSelecting = false
    private var tapTimeout: Runnable? = null
    private var edgeScrolling = false

    // Settings
    private var sensitivity = 1.5f
    private var scrollSpeed = 0.2f
    private var scrollInvert = true

    // Drag-to-move state
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val tapThresholdPx = TAP_MOVE_THRESHOLD * density

    var onClose: (() -> Unit)? = null

    init {
        loadSettings()
        buildUI()
        elevation = 8 * density
    }

    fun loadSettings() {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        sensitivity = prefs.getInt("tp_sensitivity", 150) / 100f
        scrollSpeed = prefs.getInt("tp_scroll_speed", 20) / 100f
        scrollInvert = prefs.getBoolean("tp_invert_scroll", true)
    }

    fun savePosition() {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit()
            .putFloat("floating_tp_x", translationX)
            .putFloat("floating_tp_y", translationY)
            .apply()
    }

    fun restorePosition() {
        val parent = parent as? View ?: return
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val defaultX = (parent.width - width - dp(16)).toFloat().coerceAtLeast(0f)
        val defaultY = (parent.height * 0.55f)
        translationX = prefs.getFloat("floating_tp_x", defaultX)
        translationY = prefs.getFloat("floating_tp_y", defaultY)
        clampToParent()
    }

    fun clampToParent() {
        val parent = parent as? View ?: return
        translationX = translationX.coerceIn(0f, (parent.width - width).toFloat().coerceAtLeast(0f))
        translationY = translationY.coerceIn(0f, (parent.height - height).toFloat().coerceAtLeast(0f))
    }

    /** Resize to fraction of parent (call after parent is laid out) */
    fun updateSize() {
        val parent = parent as? View ?: return
        val w = (parent.width * 0.28f).toInt().coerceIn(dp(180), dp(300))
        val h = (parent.height * 0.30f).toInt().coerceIn(dp(140), dp(240))
        layoutParams = (layoutParams ?: LayoutParams(w, h)).apply {
            width = w; height = h
        }
    }

    private fun buildUI() {
        // Background: dark semi-transparent with border
        background = GradientDrawable().apply {
            setColor(0xE6252526.toInt())
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), 0xFF404040.toInt())
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // === Drag handle bar + close button ===
        val handleBar = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(26))
        }

        // Grip visual (centered dots)
        handleBar.addView(View(context).apply {
            layoutParams = LayoutParams(dp(36), dp(4), Gravity.CENTER).apply { topMargin = dp(11) }
            background = GradientDrawable().apply {
                setColor(0xFF555555.toInt())
                cornerRadius = dp(2).toFloat()
            }
        })

        // Close button
        handleBar.addView(TextView(context).apply {
            text = "\u2715"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            setPadding(dp(8), dp(2), dp(8), dp(2))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL)
            setOnClickListener { onClose?.invoke() }
        })

        // Drag to move
        handleBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragOffsetX = event.rawX - this@FloatingTouchpad.translationX
                    dragOffsetY = event.rawY - this@FloatingTouchpad.translationY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    this@FloatingTouchpad.translationX = event.rawX - dragOffsetX
                    this@FloatingTouchpad.translationY = event.rawY - dragOffsetY
                    clampToParent()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    savePosition()
                    true
                }
                else -> false
            }
        }
        root.addView(handleBar)

        // === Touch area ===
        val touchArea = object : View(context) {
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                return handleTouchAreaEvent(event)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { setMargins(dp(6), 0, dp(6), dp(4)) }
            background = GradientDrawable().apply {
                setColor(0x08FFFFFF)
                cornerRadius = dp(4).toFloat()
                setStroke(dp(1), 0xFF333333.toInt())
            }
            isClickable = true
        }
        root.addView(touchArea)

        // === Buttons: L | M | R ===
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36)
            ).apply { setMargins(dp(6), 0, dp(6), dp(6)) }
        }

        fun makeBtn(label: String, weight: Float = 1f) = Button(context).apply {
            text = label; textSize = 12f; isAllCaps = false
            setTextColor(0xFFCCCCCC.toInt())
            background = GradientDrawable().apply {
                setColor(0xFF333333.toInt())
                cornerRadius = dp(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                marginStart = dp(2); marginEnd = dp(2)
            }
            setPadding(0, 0, 0, 0)
        }

        val leftBtn = makeBtn("L")
        val midBtn = makeBtn("M", 0.6f)
        val rightBtn = makeBtn("R")

        // Left: hold-drag capable
        leftBtn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { overlayManager.performMouseDown(0); v.isPressed = true; true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { overlayManager.performMouseUp(0); v.isPressed = false; true }
                else -> false
            }
        }
        midBtn.setOnClickListener { overlayManager.performClick(1) }
        rightBtn.setOnClickListener { overlayManager.performClick(2) }

        btnRow.addView(leftBtn)
        btnRow.addView(midBtn)
        btnRow.addView(rightBtn)
        root.addView(btnRow)

        addView(root)
    }

    fun handleTouchAreaEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                FileLogger.d("FloatingTP", "DOWN x=${event.x} y=${event.y}")
                tracking = true
                fingerCount = 1
                lastX = event.x; lastY = event.y
                totalMove = 0f
                tapStart = System.currentTimeMillis()
                tapTimeout?.let { removeCallbacks(it) }
                tapTimeout = null
                // Detect right-edge scroll zone
                edgeScrolling = event.x > width * (1f - EDGE_SCROLL_FRACTION)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                fingerCount = event.pointerCount
                if (fingerCount >= 2) scrollLastY = event.getY(0)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val dx = event.x - lastX
                val dy = event.y - lastY
                totalMove += Math.abs(dx) + Math.abs(dy)

                if (event.pointerCount >= 2) {
                    val scrollDy = (event.getY(0) - scrollLastY) / density
                    scrollLastY = event.getY(0)
                    val dir = if (scrollInvert) -1f else 1f
                    overlayManager.performScroll(scrollDy * scrollSpeed * dir)
                } else if (edgeScrolling) {
                    // Right-edge single-finger scroll
                    val dyDp = dy / density
                    val dir = if (scrollInvert) -1f else 1f
                    overlayManager.performScroll(dyDp * scrollSpeed * dir)
                } else {
                    val dxDp = dx / density
                    val dyDp = dy / density
                    overlayManager.moveCursor(dxDp * sensitivity, dyDp * sensitivity)

                    if (!isDragSelecting && totalMove > tapThresholdPx &&
                        System.currentTimeMillis() - lastTapTime < DOUBLE_TAP_TIMEOUT) {
                        isDragSelecting = true
                        overlayManager.performMouseDown(0)
                    }
                }
                lastX = event.x; lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                FileLogger.d("FloatingTP", "UP totalMove=$totalMove")
                if (isDragSelecting) {
                    overlayManager.performMouseUp(0)
                    isDragSelecting = false
                    tracking = false
                    return true
                }

                val elapsed = System.currentTimeMillis() - tapStart
                val isTap = totalMove < tapThresholdPx && elapsed < TAP_TIMEOUT

                if (isTap) {
                    if (fingerCount >= 2) {
                        overlayManager.performClick(2)
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < DOUBLE_TAP_TIMEOUT) {
                            tapTimeout?.let { removeCallbacks(it) }
                            tapTimeout = null
                            overlayManager.performDoubleClick()
                            lastTapTime = 0
                        } else {
                            lastTapTime = now
                            val runnable = Runnable {
                                overlayManager.performClick(0)
                                tapTimeout = null
                            }
                            tapTimeout = runnable
                            postDelayed(runnable, DOUBLE_TAP_TIMEOUT)
                        }
                    }
                }
                tracking = false
                fingerCount = 0
                edgeScrolling = false
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> return true
        }
        return false
    }
}
