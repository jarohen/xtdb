-- Run with: psql -h localhost -p 5432 ticks -f modules/kdb/demo/queries-ticks.sql

-- psql prints each query before its result
\set ECHO queries

\echo ''
\echo '== The book now: the latest tick per sym.'
\echo ''
SELECT _id AS sym, price, size, _valid_from
FROM trade ORDER BY _id
;

\echo ''
\echo '== AAPL history, newest first: every tick is a version of its sym,'
\echo '   valid until the next one (each _valid_to is the _valid_from of the row above).'
\echo ''
SELECT price, size, _valid_from, _valid_to
FROM trade FOR ALL VALID_TIME
WHERE _id = 'AAPL'
ORDER BY _valid_from DESC LIMIT 5
;

\echo ''
\echo '== Versions taken per sym so far.'
\echo ''
SELECT _id AS sym, COUNT(*) AS ticks
FROM trade FOR ALL VALID_TIME
ORDER BY _id
;

\echo ''
\echo '== The book as it stood five seconds ago (compare with the first query).'
\echo ''
SELECT _id AS sym, price, _valid_from
FROM trade FOR VALID_TIME AS OF CURRENT_TIMESTAMP - INTERVAL 'PT5S'
ORDER BY _id
;
