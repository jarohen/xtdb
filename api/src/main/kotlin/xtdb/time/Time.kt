@file:JvmName("Time")

package xtdb.time

import xtdb.types.ZonedDateTimeRange
import java.lang.Math.multiplyExact
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
import java.time.format.DateTimeFormatter.ISO_LOCAL_TIME
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoUnit.MICROS
import java.time.temporal.TemporalAccessor
import java.time.temporal.TemporalQueries
import java.time.temporal.TemporalQuery

const val MILLI_HZ = 1_000
const val MICRO_HZ = 1_000_000
const val NANO_HZ = 1_000_000_000

internal val Long.secondsToMillis get() = multiplyExact(this, MILLI_HZ)
internal val Long.secondsToMicros get() = multiplyExact(this, MICRO_HZ)
val Long.secondsToNanos get() = multiplyExact(this, NANO_HZ)

val Long.microsAsInstant get(): Instant = Instant.EPOCH.plus(this, MICROS)

internal val Long.millisToSecondsPart get() = this / MILLI_HZ
internal val Long.microsToSecondsPart get() = this / MICRO_HZ
internal val Long.nanosToSecondsPart get() = this / NANO_HZ

internal val Int.nanoPartToMillis get() = this / (NANO_HZ / MILLI_HZ)
internal val Int.nanoPartToMicros get() = this / (NANO_HZ / MICRO_HZ)

internal val Long.millisToNanosPart get() = multiplyExact(this % MILLI_HZ, (NANO_HZ / MILLI_HZ))
internal val Long.microsToNanosPart get() = multiplyExact(this % MICRO_HZ, (NANO_HZ / MICRO_HZ))
internal val Long.nanosToNanosPart get() = this % NANO_HZ

private val OFFSET_AND_ZONE_FORMATTER = DateTimeFormatterBuilder()
    .optionalStart()
    .appendOffset("+HH:mm", "Z")
    .optionalEnd()
    .optionalStart()
    .appendLiteral('[')
    .parseCaseSensitive()
    .appendZoneRegionId()
    .appendLiteral(']')
    .optionalEnd()
    .toFormatter()

val SQL_TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatterBuilder()
        .append(ISO_LOCAL_DATE)
        .optionalStart()
        .appendLiteral('T')
        .append(ISO_LOCAL_TIME)
        .optionalEnd()
        .appendOptional(OFFSET_AND_ZONE_FORMATTER)
        .toFormatter()

private fun LocalDateTime.maybeWithZoneFrom(acc: TemporalAccessor) =
    acc.query(TemporalQueries.zone())?.let { atZone(it) } ?: this

fun String.asSqlTimestamp(): TemporalAccessor =
    this.replace(' ', 'T')
        .let { s ->
            SQL_TIMESTAMP_FORMATTER.parseBest(
                s,
                ZonedDateTime::from,
                { OffsetDateTime.from(it).toZonedDateTime() },
                { LocalDateTime.from(it).maybeWithZoneFrom(it) },
                { LocalDate.from(it).atStartOfDay().maybeWithZoneFrom(it) },
            )
        }

fun String.asZonedDateTime(): ZonedDateTime =
    this.replace(' ', 'T')
        .let { s ->
            SQL_TIMESTAMP_FORMATTER.parseBest(
                s,
                ZonedDateTime::from,
                { OffsetDateTime.from(it).toZonedDateTime() },
                { LocalDate.from(it).atStartOfDay().atZone(it.query(TemporalQueries.zone())) },
            )
        } as ZonedDateTime

fun String.asOffsetDateTime() = asZonedDateTime().toOffsetDateTime()
fun String.asInstant() = asZonedDateTime().toInstant()

private fun <T: TemporalAccessor> String.asTemporal(q: TemporalQuery<T>): T =
    SQL_TIMESTAMP_FORMATTER.parse(replace(' ', 'T'), q)

fun String.asLocalDateTime() = asTemporal(LocalDateTime::from)

val TEMPORAL_COL_NAMES = setOf("_valid_from", "_valid_to", "_system_from", "_system_to")