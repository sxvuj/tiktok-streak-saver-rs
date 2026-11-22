package com.sxvuj.streaksaver.service

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Live state of the streak run, shared between [StreakService] and the UI. Both live in the same
 * process, so a plain in-memory flow is enough; after a process death it simply starts idle again.
 */
object RunStatus {
    data class State(
        val running: Boolean = false,
        val index: Int = 0,
        val total: Int = 0,
        val current: String = "",
        val stopping: Boolean = false,
    )

    val state = MutableStateFlow(State())
}
