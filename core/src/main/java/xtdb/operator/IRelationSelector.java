package xtdb.operator;

import xtdb.vector.RelationReader;

public interface IRelationSelector {
    /**
     * @param params a single-row indirect relation containing the params for this invocation - maybe a view over a bigger param relation.
     */
    int[] select(RelationReader readRelation, RelationReader params);
}
