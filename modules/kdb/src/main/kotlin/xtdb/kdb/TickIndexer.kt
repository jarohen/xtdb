package xtdb.kdb

import kotlinx.serialization.modules.PolymorphicModuleBuilder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import xtdb.api.error.Unsupported
import xtdb.api.tx.OpenTx
import xtdb.arrow.RelationReader
import java.util.ServiceLoader
import com.google.protobuf.Any as ProtoAny

/** Decides how one tickerplant update becomes writes in the tx [OpenTx] — one tx per `upd` message. */
interface TickIndexer : AutoCloseable {

    /**
     * Writes one `upd` message into [openTx]: [rel] holds the rows of kdb+ table [table], one Arrow column per kdb+
     * column (symbols and strings as UTF8, timestamps as INSTANT, timespans as DURATION, and so on).
     */
    fun indexTicks(table: String, rel: RelationReader, openTx: OpenTx)

    override fun close() = Unit

    interface Factory {
        fun open(): TickIndexer

        companion object {
            private val registrations = ServiceLoader.load(Registration::class.java).toList()
            private val registrationsByTag = registrations.associateBy { it.protoTag }
            private val registrationsByClass = registrations.associateBy { it.factoryClass }

            val serializersModule = SerializersModule {
                for (reg in registrations)
                    include(reg.serializersModule)

                polymorphic(Factory::class) {
                    for (reg in registrations)
                        reg.registerSerde(this)
                }
            }

            fun fromProto(any: ProtoAny): Factory {
                val reg = registrationsByTag[any.typeUrl] ?: error("unknown tick indexer: ${any.typeUrl}")
                return reg.fromProto(any)
            }

            @Suppress("UNCHECKED_CAST")
            fun toProto(factory: Factory): ProtoAny {
                val reg = registrationsByClass[factory.javaClass] as Registration<Factory>?
                    ?: throw Unsupported(
                        "tick indexer ${factory.javaClass.name} can't be persisted as a secondary database — it has no Registration",
                        "xtdb.kdb/indexer-not-serializable",
                        mapOf("indexer" to factory.javaClass.name),
                    )
                return reg.toProto(factory)
            }
        }
    }

    interface Registration<F : Factory> {
        val protoTag: String
        val factoryClass: Class<F>
        fun toProto(factory: F): ProtoAny
        fun fromProto(msg: ProtoAny): F
        fun registerSerde(builder: PolymorphicModuleBuilder<Factory>)
        val serializersModule: SerializersModule get() = SerializersModule {}
    }
}
