/ UNTESTED - a feed handler: every 50ms, publishes a random-walk trade per sym to the tickerplant on :5010,
/ a tenth of them block trades. Start the tickerplant first: q tick.q sym tplog -p 5010 (with sym.q as tick/sym.q)
/ Run: q feed.q
h:hopen 5010
syms:`AAPL`MSFT`GOOG
px:syms!100 300 150f
.z.ts:{
  px+:count[syms]?1f-.5;                                      / random walk, one step per sym
  n:count syms;
  sz:?[0=n?10;10000+n?40000;1+n?500];                         / sizes: ~1 in 10 is a block
  (neg h)(`.u.upd;`trade;(syms;px syms;`int$sz))
 }
\t 50
