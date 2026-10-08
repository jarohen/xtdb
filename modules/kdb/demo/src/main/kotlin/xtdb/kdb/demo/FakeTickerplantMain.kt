package xtdb.kdb.demo

import com.kx.c
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * A stand-in for a kdb+tick tickerplant, for running the demo without q: once a subscriber has attached, publishes
 * a random-walk `trade` tick for each of a few syms every 50ms, with `time` as kdb+tick does (a timespan
 * since midnight).
 * One tick in ten is a block trade (size over 10,000).
 *
 * Runs until killed.
 */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: 5010
    val schema = c.Flip(
        arrayOf("time", "sym", "price", "size"),
        arrayOf<Any>(arrayOf<c.Timespan>(), arrayOf<String>(), doubleArrayOf(), intArrayOf()),
    )

    FakeTickerplant(mapOf("trade" to schema), port0 = port).use { tp ->
        println("fake tickerplant listening on $port; waiting for a subscriber")
        tp.subscribed.await()
        println("subscriber connected; publishing a tick per sym every 0.5s (more subscribers can join any time)")

        val prices = mutableMapOf("AAPL" to 100.0, "MSFT" to 300.0, "GOOG" to 150.0)
        while (true) {
            val syms = prices.keys.toList()
            for (s in syms) prices[s] = prices.getValue(s) + Random.nextDouble(-1.0, 1.0)

            val zone = ZoneId.systemDefault()
            val sinceMidnight = Duration.between(LocalDate.now(zone).atStartOfDay(zone), ZonedDateTime.now(zone))

            tp.publish(
                "trade", c.Flip(
                    arrayOf("time", "sym", "price", "size"),
                    arrayOf<Any>(
                        Array(syms.size) { c.Timespan(sinceMidnight.toNanos()) },
                        syms.toTypedArray(),
                        DoubleArray(syms.size) { prices.getValue(syms[it]) },
                        IntArray(syms.size) { if (Random.nextInt(10) == 0) Random.nextInt(10_000, 50_000) else Random.nextInt(1, 500) },
                    ),
                )
            )
            Thread.sleep(50)
        }
    }
}
