-- Run with: psql -h localhost -p 5432 bars -f modules/kdb/demo/queries-bars.sql

-- psql prints each query before its result
\set ECHO queries

\echo ''
\echo '== One-minute OHLC bars: one entity per sym, one version per minute.'
\echo '   Each is valid from its minute start to its end - or to now, while the minute is still open.'
\echo ''
SELECT _id AS sym, _valid_from, _valid_to, open, high, low, close, size
FROM trade_bars
  FOR VALID_TIME FROM CURRENT_TIMESTAMP - INTERVAL 'PT5M' TO CURRENT_TIMESTAMP
ORDER BY _id, _valid_from
;
