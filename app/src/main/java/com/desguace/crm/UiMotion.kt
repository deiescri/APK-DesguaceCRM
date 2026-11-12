package com.desguace.crm

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateDecelerateInterpolator

/** Animaciones cortas y ligeras compartidas por las pantallas de DesguaceCRM. */
object UiMotion {
    private const val SCREEN_DURATION_MS = 220L
    private const val ITEM_DURATION_MS = 180L
    private const val PRESS_DURATION_MS = 90L

    private fun animationsEnabled(): Boolean = try {
        ValueAnimator.areAnimatorsEnabled()
    } catch (_: Throwable) {
        true
    }

    private fun dp(view: View, value: Float): Float =
        value * view.resources.displayMetrics.density

    /** Entrada discreta de pantalla; respeta la preferencia del sistema de reducir animaciones. */
    fun enterScreen(root: View?) {
        if (root == null) return
        root.post {
            if (!root.isAttachedToWindow) return@post
            if (!animationsEnabled()) {
                root.alpha = 1f
                root.translationY = 0f
                return@post
            }
            root.animate().cancel()
            root.alpha = 0f
            root.translationY = dp(root, 9f)
            root.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(0L)
                .setDuration(SCREEN_DURATION_MS)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
        }
    }

    /** Anima como máximo ocho filas: da sensación de fluidez sin ralentizar listas largas. */
    fun animateItems(container: ViewGroup?, maxItems: Int = 8) {
        if (container == null || container.childCount == 0) return
        if (!animationsEnabled()) {
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                child.alpha = 1f
                child.translationY = 0f
            }
            return
        }
        val count = minOf(container.childCount, maxItems.coerceIn(0, 8))
        for (i in 0 until count) {
            val child = container.getChildAt(i)
            child.animate().cancel()
            child.alpha = 0f
            child.translationY = dp(child, 7f)
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(minOf(i * 16L, 96L))
                .setDuration(ITEM_DURATION_MS)
                .setInterpolator(DecelerateInterpolator(1.4f))
                .start()
        }
    }

    /** Pulso táctil muy sutil; no consume el evento y mantiene el comportamiento de clic/foco. */
    fun bindPressFeedback(view: View?) {
        if (view == null || !view.isClickable) return
        view.setOnTouchListener { touched, event ->
            if (animationsEnabled()) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touched.animate().cancel()
                        touched.animate()
                            .scaleX(0.985f)
                            .scaleY(0.985f)
                            .setDuration(PRESS_DURATION_MS)
                            .setInterpolator(AccelerateDecelerateInterpolator())
                            .start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        touched.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(120L)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                }
            }
            false
        }
    }

    /** Expande detalles con fundido; la contracción es inmediata para evitar saltos de altura. */
    fun toggleDetails(view: View?) {
        if (view == null) return
        view.animate().cancel()
        if (view.visibility == View.VISIBLE) {
            view.visibility = View.GONE
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        view.visibility = View.VISIBLE
        if (!animationsEnabled()) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        view.alpha = 0f
        view.translationY = dp(view, 5f)
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(160L)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .start()
    }
}
