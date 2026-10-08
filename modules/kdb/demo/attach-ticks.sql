-- The default indexer: one document per tick, _id = sym, valid-from = the tick's time.
ATTACH DATABASE ticks WITH $$
  log: !Local
    path: modules/kdb/build/demo-state/ticks-log
  storage: !Local
    path: modules/kdb/build/demo-state/ticks-storage
  externalSource: !Kdb
    port: 5010
    indexer: !Ticks {}
$$;
