package com.trace.app.model

data class Record(
    val id: Long,
    val amount: Double,
    val category: String,
    val merchant: String?,
    val note: String?,
    val timestamp: Long,
    val isNight: Boolean
)
