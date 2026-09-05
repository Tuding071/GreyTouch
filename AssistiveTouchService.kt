package com.grey.touch

import android.accessibilityservice.AccessibilityService
import android.content.Context
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
    private lateinit var bubble: FrameLayout
    private lateinit var glyph: android.widget.ImageView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences
    private lateinit var vibrator: Vibrator

    private var screenWidth = 0
    private var screenHeight = 0
    private var bubbleSizePx = 0

    // gesture tracking
    private var downRawX = 0f
    private var downRawY = 0f
    private var initialX = 0
    private var initialY = 0
    private var downTime = 0L
    private var isDragging = false
    private var longPressTriggered = false
    private var moved = false

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        isDragging = true
        vibrate()
    }

    companion object {
        const val TAP_MAX_MS = 200L
        const val HOLD_MIN_MS = 300L
        const val MOVE_THRESHOLD_PX = 20
        const val SWIPE_THRESHOLD_PX = 60
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("assistive_touch_prefs", Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        measureScreen()

        val density = resources.displayMetrics.density
        bubbleSizePx = (56 * density).toInt()

        glyph = android.widget.ImageView(this).apply {
            val glyphSize = (24 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(glyphSize, glyphSize, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.WHITE)
            }
        }

        bubble = FrameLayout(this).apply {
            addView(glyph)
        }
        applyIdleAppearance()

        val savedRatioX = prefs.getFloat("anchor_edge", 1f) // 0f = left, 1f = right
        val savedRatioY = prefs.getFloat("ratio_y", 0.5f)

        val startX = if (savedRatioX <= 0f) 0 else screenWidth - bubbleSizePx
        val startY = ((screenHeight - bubbleSizePx) * savedRatioY).toInt()

        params = WindowManager.LayoutParams(
            bubbleSizePx,
            bubbleSizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY.coerceIn(0, maxOf(0, screenHeight - bubbleSizePx))
        }

        bubble.setOnTouchListener { _, event -> handleTouch(event) }
        windowManager.addView(bubble, params)
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
        if (!::windowManager.isInitialized || !::bubble.isInitialized) return

        // re-measure after rotation and re-anchor using saved ratios (not stale pixels)
        handler.postDelayed({
            measureScreen()
            val anchorEdge = prefs.getFloat("anchor_edge", 1f)
            val ratioY = prefs.getFloat("ratio_y", 0.5f)

            params.x = if (anchorEdge <= 0f) 0 else screenWidth - bubbleSizePx
            params.y = ((screenHeight - bubbleSizePx) * ratioY).toInt()
                .coerceIn(0, maxOf(0, screenHeight - bubbleSizePx))

            windowManager.updateViewLayout(bubble, params)
        }, 150L)
    }

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(20)
        }
    }

    private fun applyIdleAppearance() {
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18 * resources.displayMetrics.density
            setColor(Color.argb(102, 20, 20, 22)) // ~40% opacity dark
            setStroke((1 * resources.displayMetrics.density).toInt(), Color.argb(64, 255, 255, 255))
        }
        (glyph.background as GradientDrawable).setColor(Color.WHITE)
    }

    private fun applyTouchedAppearance() {
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18 * resources.displayMetrics.density
            setColor(Color.argb(242, 255, 255, 255)) // ~95% opacity white
            setStroke((1 * resources.displayMetrics.density).toInt(), Color.argb(77, 0, 0, 0))
        }
        (glyph.background as GradientDrawable).setColor(Color.BLACK)
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

                if (!moved && (abs(dx) > MOVE_THRESHOLD_PX || abs(dy) > MOVE_THRESHOLD_PX)) {
                    moved = true
                    if (!longPressTriggered) handler.removeCallbacks(longPressRunnable)
                }

                if (isDragging) {
                    params.x = (initialX + dx.toInt()).coerceIn(0, screenWidth - bubbleSizePx)
                    params.y = (initialY + dy.toInt()).coerceIn(0, screenHeight - bubbleSizePx)
                    windowManager.updateViewLayout(bubble, params)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                applyIdleAppearance()

                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                val elapsed = System.currentTimeMillis() - downTime

                if (isDragging) {
                    snapToNearestEdge()
                } else if (!moved && elapsed < TAP_MAX_MS) {
                    vibrate()
                    performGlobalAction(GLOBAL_ACTION_BACK)
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
        val centerX = params.x + bubbleSizePx / 2
        val snapToLeft = centerX < screenWidth / 2
        val targetX = if (snapToLeft) 0 else screenWidth - bubbleSizePx

        val startX = params.x
        val distance = targetX - startX
        val steps = 8
        var step = 0

        val animator = object : Runnable {
            override fun run() {
                step++
                val progress = step.toFloat() / steps
                params.x = (startX + distance * progress).toInt()
                windowManager.updateViewLayout(bubble, params)
                if (step < steps) {
                    handler.postDelayed(this, 8L)
                } else {
                    params.x = targetX
                    windowManager.updateViewLayout(bubble, params)
                    saveAnchor(snapToLeft)
                }
            }
        }
        handler.post(animator)
    }

    private fun saveAnchor(isLeft: Boolean) {
        val ratioY = params.y.toFloat() / maxOf(1, screenHeight - bubbleSizePx)
        prefs.edit()
            .putFloat("anchor_edge", if (isLeft) 0f else 1f)
            .putFloat("ratio_y", ratioY.coerceIn(0f, 1f))
            .apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (::bubble.isInitialized && ::windowManager.isInitialized) {
            windowManager.removeView(bubble)
        }
    }
}
