package xtdb.arrow

import xtdb.api.query.IKeyFn

/**
 * A [ValueReader] over a vector's rows in order, as handed out by [VectorReader.rowIterator]:
 * `next()` moves this reader to the following row and returns it.
 */
interface RowValueReader : ValueReader, Iterator<RowValueReader> {
    /** This row materialised, as [VectorReader.getObject] with [keyFn]. */
    fun readValue(keyFn: IKeyFn<*>): Any?

    /** Reads as [vec]'s own [VectorReader.valueReader], moved a row at a time. */
    class ForVector private constructor(
        private val vec: VectorReader, private val rdr: ValueReader
    ) : RowValueReader, ValueReader by rdr {

        constructor(vec: VectorReader) : this(vec, vec.valueReader())

        // not forwarded until the first `next()`: a struct or union reader can't be positioned at -1
        override var pos: Int = -1
            set(value) {
                field = value
                rdr.pos = value
            }

        override fun readValue(keyFn: IKeyFn<*>) = vec.getObject(pos, keyFn)

        override fun hasNext() = pos + 1 < vec.valueCount

        override fun next(): RowValueReader {
            if (!hasNext()) throw NoSuchElementException()
            pos++
            return this
        }
    }
}
