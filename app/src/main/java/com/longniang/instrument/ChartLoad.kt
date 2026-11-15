package com.longniang.instrument

/** UI-thread ownership of the only result allowed to open a chart. */
internal class ChartLoad {
    private var next = 0L
    private var active: Long? = null
    fun begin(): Long? {
        if (active != null) return null
        return (++next).also { active = it }
    }
    fun owns(id: Long) = active == id
    fun finish(id: Long): Boolean {
        if (!owns(id)) return false
        active = null
        return true
    }
    fun cancel() { active = null }
}
