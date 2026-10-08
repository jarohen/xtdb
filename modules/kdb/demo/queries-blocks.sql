-- Run with: psql -h localhost -p 5432 blocks -f modules/kdb/demo/queries-blocks.sql

\echo '== Block trades only (size >= 10,000), newest first, with notional = price * size.'
SELECT _id, price, size, notional FROM block_trades FOR ALL VALID_TIME ORDER BY _valid_from DESC LIMIT 10;
