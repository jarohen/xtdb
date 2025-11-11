package xtdb.operator.join

import xtdb.arrow.EquiComparator2
import xtdb.arrow.EquiComparator3
import xtdb.arrow.RelationReader
import xtdb.arrow.VectorReader
import xtdb.arrow.FieldName

interface ComparatorFactory {
    companion object {

        @JvmStatic
        fun ComparatorFactory.build(
            buildSide: BuildSide, probeRel: RelationReader, probeKeyColNames: List<FieldName>
        ): EquiComparator3 {
            val buildRel = buildSide.dataRel

            val buildKeyCols = buildSide.keyColNames.map { buildRel[it] }
            val probeKeyCols = probeKeyColNames.map { probeRel[it] }

            return buildKeyCols.zip(probeKeyCols)
                .map { (buildCol, probeCol) -> buildEqui(buildCol, probeCol) }
                .plus(listOfNotNull(buildTheta(buildRel, probeRel)))
                .reduceOrNull(EquiComparator3::and)
                ?: EquiComparator3 { _, _ -> true }
        }
    }

    fun buildEqui(buildCol: VectorReader, probeCol: VectorReader): EquiComparator3
    fun buildTheta(buildRel: RelationReader, probeRel: RelationReader): EquiComparator3?
}