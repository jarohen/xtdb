-- The example OHLC indexer (demo/src/main/kotlin/xtdb/kdb/demo/OhlcBarsIndexer.kt): one-minute bars per sym.
ATTACH DATABASE bars WITH $$
  log: !Local
    path: modules/kdb/build/demo-state/bars-log
  storage: !Local
    path: modules/kdb/build/demo-state/bars-storage
  externalSource: !Kdb
    port: 5010
    indexer: !OhlcBars
      tables: [trade]
$$;
