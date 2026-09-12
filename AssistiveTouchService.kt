package com.grey.touch

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import kotlin.math.abs

class AssistiveTouchService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleContainer: FrameLayout
    private lateinit var mainCard: FrameLayout
    private lateinit var layer1: FrameLayout
    private lateinit var layer2: FrameLayout
    private lateinit var layer3: FrameLayout
    private lateinit var glyph: android.widget.ImageView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences
    private var vibrator: Vibrator? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var bubbleSizePx = 0
    private var moveThresholdPx = 0
    private var offsetStepPx = 0

    private var downRawX = 0f
    private var downRawY = 0f
    private var initialX = 0
    private var initialY = 0
    private var downTime = 0L
    private var isDragging = false
    private var longPressTriggered = false
    private var moved = false
    private var lastTapUpTime = 0L

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        isDragging = true
        vibrate()
    }

    companion object {
        const val TAP_MAX_MS = 200L
        const val HOLD_MIN_MS = 210L
        const val DOUBLE_TAP_GAP_MS = 200L
        const val MOVE_THRESHOLD_DP = 50
        const val SWIPE_THRESHOLD_PX = 60
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("assistive_touch_prefs", Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        vibrator = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            null
        }

        measureScreen()

        val density = resources.displayMetrics.density
        bubbleSizePx = (56 * density).toInt()
        moveThresholdPx = (MOVE_THRESHOLD_DP * density).toInt()
        offsetStepPx = (4 * density).toInt()

        buildBubbleViews(density)
        applyIdleAppearance()

        val savedRatioX = prefs.getFloat("anchor_edge", 1f)
        val savedRatioY = prefs.getFloat("ratio_y", 0.5f)

        // container is bigger than the card to leave room for the offset layers
        val containerPad = offsetStepPx * 3
        val containerSize = bubbleSizePx + containerPad

        val startX = if (savedRatioX <= 0f) 0 else screenWidth - containerSize
        val startY = ((screenHeight - containerSize) * savedRatioY).toInt()

        params = WindowManager.LayoutParams(
            containerSize,
            containerSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY.coerceIn(0, maxOf(0, screenHeight - containerSize))
        }

        bubbleContainer.setOnTouchListener { _, event -> handleTouch(event) }
        windowManager.addView(bubbleContainer, params)
    }

    private fun buildBubbleViews(density: Float) {
        val containerPad = offsetStepPx * 3
        val containerSize = bubbleSizePx + containerPad

        bubbleContainer = FrameLayout(this)

        // layer3 = furthest back (most offset, most faded)
        layer3 = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx).apply {
                leftMargin = offsetStepPx * 3
                topMargin = offsetStepPx * 3
            }
        }
        layer2 = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx).apply {
                leftMargin = offsetStepPx * 2
                topMargin = offsetStepPx * 2
            }
        }
        layer1 = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx).apply {
                leftMargin = offsetStepPx
                topMargin = offsetStepPx
            }
        }

        glyph = android.widget.ImageView(this).apply {
            val glyphSize = (24 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(glyphSize, glyphSize, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.WHITE)
            }
        }

        mainCard = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx).apply {
                leftMargin = 0
                topMargin = 0
            }
            addView(glyph)
        }

        bubbleContainer.addView(layer3)
        bubbleContainer.addView(layer2)
        bubbleContainer.addView(layer1)
        bubbleContainer.addView(mainCard)
    }

    private fun measureScreen() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::windowManager.isInitialized || !::bubbleContainer.isInitialized) return

        handler.postDelayed({
            measureScreen()
            val anchorEdge = prefs.getFloat("anchor_edge", 1f)
            val ratioY = prefs.getFloat("ratio_y", 0.5f)
            val containerSize = params.width

            params.x = if (anchorEdge <= 0f) 0 else screenWidth - containerSize
            params.y = ((screenHeight - containerSize) * ratioY).toInt()
                .coerceIn(0, maxOf(0, screenHeight - containerSize))

            windowManager.updateViewLayout(bubbleContainer, params)
        }, 150L)
    }

    private fun vibrate(durationMs: Long = 35) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(durationMs)
            }
        } catch (e: Exception) {
            // ignore devices that block vibration
        }
    }

    private fun cardDrawable(density: Float, bg: Int, borderColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18 * density
            setColor(bg)
            setStroke((1 * density).toInt(), borderColor)
        }
    }

    private fun applyIdleAppearance() {
        val density = resources.displayMetrics.density
        // stacked layers visible, faded, offset
        layer3.background = cardDrawable(density, Color.argb(38, 20, 20, 22), Color.argb(30, 255, 255, 255))
        layer2.background = cardDrawable(density, Color.argb(58, 20, 20, 22), Color.argb(40, 255, 255, 255))
        layer1.background = cardDrawable(density, Color.argb(78, 20, 20, 22), Color.argb(50, 255, 255, 255))
        mainCard.background = cardDrawable(density, Color.argb(153, 20, 20, 22), Color.argb(64, 255, 255, 255))
        (glyph.background as GradientDrawable).setColor(Color.argb(200, 255, 255, 255))

        layer1.visibility = View.VISIBLE
        layer2.visibility = View.VISIBLE
        layer3.visibility = View.VISIBLE
    }

    private fun applyTouchedAppearance() {
        val density = resources.displayMetrics.density
        // collapse: hide the stack, main card becomes solid inverted
        layer1.visibility = View.INVISIBLE
        layer2.visibility = View.INVISIBLE
        layer3.visibility = View.INVISIBLE
        mainCard.background = cardDrawable(density, Color.argb(242, 255, 255, 255), Color.argb(77, 0, 0, 0))
        (glyph.background as GradientDrawable).setColor(Color.BLACK)
    }

    private fun lockPhone() {
        // Accessibility services can invoke the lock screen action directly on API 28+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            // fallback for older APIs: requires device admin, not wired up by default
            android.widget.Toast.makeText(this, "Lock requires Android 9+", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                initialX = params.x
                initialY = params.y
                downTime = System.currentTimeMillis()
                isDragging = false
                longPressTriggered = false
                moved = false
                applyTouchedAppearance()
                handler.postDelayed(longPressRunnable, HOLD_MIN_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY

                if (!moved && (abs(dx) > moveThresholdPx || abs(dy) > moveThresholdPx)) {
                    moved = true
                    if (!longPressTriggered) handler.removeCallbacks(longPressRunnable)
                }

                if (isDragging) {
                    params.x = (initialX + dx.toInt()).coerceIn(0, screenWidth - params.width)
                    params.y = (initialY + dy.toInt()).coerceIn(0, screenHeight - params.height)
                    windowManager.updateViewLayout(bubbleContainer, params)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                applyIdleAppearance()

                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                val elapsed = System.currentTimeMillis() - downTime
                val upTime = System.currentTimeMillis()

                if (isDragging) {
                    snapToNearestEdge()
                } else if (!moved && elapsed < TAP_MAX_MS) {
                    // check for double tap
                    val gapSinceLastTap = upTime - lastTapUpTime
                    if (gapSinceLastTap < DOUBLE_TAP_GAP_MS) {
                        vibrate(45)
                        lockPhone()
                        lastTapUpTime = 0L // reset so a 3rd quick tap doesn't chain
                    } else {
                        vibrate()
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        lastTapUpTime = upTime
                    }
                } else {
                    val swipedUp = dy < -SWIPE_THRESHOLD_PX && abs(dy) > abs(dx)
                    val swipedDown = dy > SWIPE_THRESHOLD_PX && abs(dy) > abs(dx)
                    when {
                        swipedUp -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                        swipedDown -> android.widget.Toast.makeText(
                            this, "Menu: coming soon", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                isDragging = false
                return true
            }
        }
        return false
    }

    private fun snapToNearestEdge() {
        val centerX = params.x + params.width / 2
        val snapToLeft = centerX < screenWidth / 2
        val targetX = if (snapToLeft) 0 else screenWidth - params.width

        val startX = params.x
        val distance = targetX - startX
        val steps = 8
        var step = 0

        val animator = object : Runnable {
            override fun run() {
                step++
                val progress = step.toFloat() / steps
                params.x = (startX + distance * progress).toInt()
                windowManager.updateViewLayout(bubbleContainer, params)
                if (step < steps) {
                    handler.postDelayed(this, 8L)
                } else {
                    params.x = targetX
                    windowManager.updateViewLayout(bubbleContainer, params)
                    saveAnchor(snapToLeft)
                }
            }
        }
        handler.post(animator)
    }

    private fun saveAnchor(isLeft: Boolean) {
        val ratioY = params.y.toFloat() / maxOf(1, screenHeight - params.height)
        prefs.edit()
            .putFloat("anchor_edge", if (isLeft) 0f else 1f)
            .putFloat("ratio_y", ratioY.coerceIn(0f, 1f))
            .apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (::bubbleContainer.isInitialized && ::windowManager.isInitialized) {
            windowManager.removeView(bubbleContainer)
        }
    }
}
