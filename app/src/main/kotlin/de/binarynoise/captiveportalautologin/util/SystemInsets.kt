package de.binarynoise.captiveportalautologin.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach

/** Use insets remaining after the platform action bar has handled its own. */
fun View.applySystemInsets() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    fitsSystemWindows = false
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime() or
            WindowInsetsCompat.Type.mandatorySystemGestures())
        view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
        WindowInsetsCompat.CONSUMED
    }
    doOnAttach { ViewCompat.requestApplyInsets(it) }
}
