package io.legado.app.help.book

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Material3 DatePicker 的毫秒是 UTC 当天 0 点。
 * 用系统时区换算的话，东八区每次在选择器里点确定都会把日期减一天，
 * 起始日不断往前滑，模拟追读就再也藏不住后面的章节。
 */
fun localDateToPickerMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun pickerMillisToLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
