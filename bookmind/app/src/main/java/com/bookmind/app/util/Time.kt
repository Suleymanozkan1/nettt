package com.bookmind.app.util

import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Injectable clock so time-dependent logic (due dates, day boundaries) is testable. */
interface AppClock {
    fun now(): Long
    fun zone(): ZoneId
    fun today(): LocalDate = java.time.Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate()
    fun startOfToday(): Long = today().atStartOfDay(zone()).toInstant().toEpochMilli()
    fun endOfToday(): Long = today().plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli() - 1
}

@Singleton
class SystemClock @Inject constructor() : AppClock {
    override fun now(): Long = System.currentTimeMillis()
    // Read every time: the user may travel or change the device time zone while the app runs.
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

fun newId(): String = UUID.randomUUID().toString()
