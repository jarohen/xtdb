-- Run with: psql -h localhost -p 5432 bars -f modules/kdb/demo/queries-bars.sql

\echo '== One-minute OHLC bars per sym, folded from the same ticks by !OhlcBars.'
\echo '   The bar for the current minute keeps updating until the minute ends.'
SELECT _id, open, high, low, close, size FROM trade_bars FOR ALL VALID_TIME ORDER BY sym, minute;
