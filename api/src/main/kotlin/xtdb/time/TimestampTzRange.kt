package xtdb.time

import xtdb.util.requiringResolve
import java.time.ZonedDateTime

interface TimestampTzRange {
    val from: ZonedDateTime?
    val to: ZonedDateTime?

    companion object {
        operator fun invoke(from: ZonedDateTime?, to: ZonedDateTime?): TimestampTzRange =
            requiringResolve("xtdb.time/->tstz-range").invoke(from, to) as TimestampTzRange
    }
}