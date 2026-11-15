package com.longniang.instrument

/** A null lock result carries no ownership and must never be posted. */
internal fun <C : Any> drawSurfaceFrame(
    acquire: () -> C?, draw: (C) -> Unit, post: (C) -> Unit,
    onSurfaceFailure: (RuntimeException) -> Unit
): Boolean {
    val canvas = try {
        acquire()
    } catch (error: IllegalStateException) {
        onSurfaceFailure(error)
        return false
    } ?: return true

    var posted = true
    try {
        draw(canvas)
    } finally {
        try {
            post(canvas)
        } catch (error: IllegalStateException) {
            onSurfaceFailure(error)
            posted = false
        } catch (error: IllegalMonitorStateException) {
            onSurfaceFailure(error)
            posted = false
        }
    }
    return posted
}
