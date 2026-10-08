-- The example block-trade indexer (demo/src/main/kotlin/xtdb/kdb/demo/BlockTradeIndexer.kt): trades of 10,000 or more.
ATTACH DATABASE blocks WITH $$
  log: !Local
    path: modules/kdb/build/demo-state/blocks-log
  storage: !Local
    path: modules/kdb/build/demo-state/blocks-storage
  externalSource: !Kdb
    port: 5010
    indexer: !BlockTrades
      minSize: 10000
$$;
