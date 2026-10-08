-- Run with: psql -h localhost -p 5432 blocks -f modules/kdb/demo/queries-blocks.sql

-- psql prints each query before its result
\set ECHO queries

\echo ''
\echo '== Block trades only (size >= 10,000), newest first, with notional = price * size.'
\echo ''
SELECT _id, price, size, notional
FROM block_trades FOR ALL VALID_TIME
ORDER BY _valid_from DESC LIMIT 10
;
