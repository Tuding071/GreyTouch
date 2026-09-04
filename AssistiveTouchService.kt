package com.grey.touch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import kotlin.math.abs

class AssistiveTouchService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubble: FrameLayout
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences

    private var screenWidth = 0
    private var screenHeight = 0
    private val bubbleSizeDp = 56

    // gesture tracking
    private var downRawX = 0f
    private var downRawY = 0f
    private var initialX = 0
    private var initialY = 0
    private var downTime = 0L
    private var isDragging = false
    private var longPressTriggered = false
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        isDragging = true
    }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("assistive_touch_prefs", Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        val density = metrics.density
        val bubbleSizePx = (bubbleSizeDp * density).toInt()

        bubble = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(160, 40, 40, 40))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = bubbleSizePx / 3.2f
                setColor(Color.argb(150, 50, 50, 50))
            }
        }

        val defaultX = screenWidth - bubbleSizePx - (8 * density).toInt()
        val defaultY = (screenHeight / 2) - (bubbleSizePx / 2)

        params = WindowManager.LayoutParams(
            bubbleSizePx,
            bubbleSizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bubble_x", defaultX)
            y = prefs.getInt("bubble_y", defaultY)
        }

        bubble.setOnTouchListener { _, event -> handleTouch(event) }
        windowManager.addView(bubble, params)
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
                handler.postDelayed(longPressRunnable, 500L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY

                if (!longPressTriggered && (abs(dx) > 20 || abs(dy) > 20)) {
                    // moved before long-press threshold -> treat as swipe, cancel long-press drag
                    handler.removeCallbacks(longPressRunnable)
                }

                if (isDragging) {
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(bubble, params)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                val elapsed = System.currentTimeMillis() - downTime

                if (isDragging) {
                    // save new position, snap to edge optional (not requested) - keep free position
                    prefs.edit().putInt("bubble_x", params.x).putInt("bubble_y", params.y).apply()
                } else {
                    val swipeThreshold = 60
                    when {
                        dy < -swipeThreshold && abs(dy) > abs(dx) -> {
                            // swipe up
                            performRecents()
                        }
                        dy > swipeThreshold && abs(dy) > abs(dx) -> {
                            // swipe down - placeholder
                            performMenuPlaceholder()
                        }
                        abs(dx) < 20 && abs(dy) < 20 && elapsed < 400 -> {
                            // tap
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                    }
                }
                isDragging = false
                return true
            }
        }
        return false
    }

    private fun performRecents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    private fun performMenuPlaceholder() {
        android.widget.Toast.makeText(this, "Menu: coming soon", android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (::bubble.isInitialized) {
            windowManager.removeView(bubble)
        }
    }
}
