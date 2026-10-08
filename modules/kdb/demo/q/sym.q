/ UNTESTED - written from memory of kdb-tick's tick/sym.q; the demo doesn't need q (see ../README.md).
/ The tickerplant's schema: tick.q prepends `time` to every update.
quote:([] time:`timespan$(); sym:`g#`symbol$(); bid:`float$(); ask:`float$(); bsize:`int$(); asize:`int$())
trade:([] time:`timespan$(); sym:`g#`symbol$(); price:`float$(); size:`int$())
