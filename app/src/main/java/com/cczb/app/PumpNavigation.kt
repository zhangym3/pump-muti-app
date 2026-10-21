package com.cczb.app

import androidx.compose.runtime.saveable.listSaver

internal enum class PumpTab(val label: String, val title: String, val icon: Int) {
    CONTROL("控制", "出水控制", R.drawable.ic_control),
    DEVICES("设备", "设备管理", R.drawable.ic_devices),
    RECORDS("记录", "任务记录", R.drawable.ic_records),
    SETTINGS("设置", "设置与调校", R.drawable.ic_settings)
}

/** Tabs keep their own expanded content. Back only acts on the visible tab. */
internal data class PumpNavigation(
    val tab: PumpTab = PumpTab.CONTROL,
    val pumpExpanded: Boolean = false,
    val recordKey: String? = null
) {
    fun select(target: PumpTab) = copy(tab = target)
    fun openPump() = copy(tab = PumpTab.DEVICES, pumpExpanded = true)
    fun toggleRecord(key: String) = copy(recordKey = key.takeUnless { it == recordKey })
    fun back(): PumpNavigation? = when {
        tab == PumpTab.DEVICES && pumpExpanded -> copy(pumpExpanded = false)
        tab == PumpTab.RECORDS && recordKey != null -> copy(recordKey = null)
        tab != PumpTab.CONTROL -> copy(tab = PumpTab.CONTROL)
        else -> null
    }

    companion object {
        val Saver = listSaver<PumpNavigation, Any>(
            save = { listOf(it.tab.name, it.pumpExpanded, it.recordKey.orEmpty()) },
            restore = { PumpNavigation(PumpTab.valueOf(it[0] as String), it[1] as Boolean,
                (it[2] as String).ifEmpty { null }) }
        )
    }
}
