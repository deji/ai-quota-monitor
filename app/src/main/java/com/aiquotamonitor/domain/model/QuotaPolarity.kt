package com.aiquotamonitor.domain.model

enum class QuotaPolarity {
    REMAINING,
    USED;

    val label: String
        get() = when (this) {
            REMAINING -> "% Left"
            USED -> "% Used"
        }

    fun toggle(): QuotaPolarity = when (this) {
        REMAINING -> USED
        USED -> REMAINING
    }
}
