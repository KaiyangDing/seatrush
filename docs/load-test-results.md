# Load test results: comparing the four lock strategies

Each test is a **rush**: 300 logged-in users try to hold seats at exactly the same moment. The
load-test client (`seatrush-loadtest`) gives every user its own virtual thread, holds them all at a
`CountDownLatch` and then releases them together through one shared `java.net.http.HttpClient`.

The same rushes ran against each lock strategy. Every strategy in a round received exactly the
same requests (same seed), each round started with a different strategy, and an unrecorded warm-up
rush came first. Every number below is the **median of 3 rounds**. Between rushes, all holds were
cancelled and the hall was checked to be empty again.

**Correctness first.** After every rush the client compared the successful answers with each
other and with the seat map the server reports. Over all 108 recorded rushes (3 experiments × 3
scenarios × 4 strategies × 3 rounds):

- no seat was ever held twice;
- the server's seat map always matched the answers;
- there were no errors (no 5xx, no timeouts, no refused connections).

## Scenarios

| Scenario | Hall | What every user asks for | What it tests |
|---|---|---|---|
| hot-seat | 1 × 10 | the same single seat | maximum contention: 1 winner, 299 losers |
| hot-row | 1 × 20 | two neighbouring seats in the one row | many overlapping multi-seat requests |
| spread | 50 × 50 | two neighbouring seats anywhere | little contention: most users win |

Columns in the tables:

- **Held**, **Taken** and **Busy** count requests. *Busy* is a 409 caused by giving up on a lock
  after the 5 s wait timeout.
- **Rush time** runs from the release of the latch until the last answer arrives.
- **p50/p95** are response times of individual requests.

## Summary: rush time in ms (lower is better)

| Scenario | Strategy | A: strategies alone | B: + precheck (default) | C: B + 50 DB connections |
|---|---|--:|--:|--:|
| hot-seat | pessimistic | 532 | **181** | 181 |
| hot-seat | optimistic | 671 | 216 | 233 |
| hot-seat | in-memory | 4281 | 3507 | 4230 |
| hot-seat | redis | 5393 (70 busy) | 2663 | 4355 |
| hot-row | pessimistic | 578 | 216 | **155** |
| hot-row | optimistic | 600 | 242 | 196 |
| hot-row | in-memory | 4004 | 1778 | 2868 |
| hot-row | redis | 1184 | 840 | 853 |
| spread | pessimistic | 1262 | 1289 | 367 |
| spread | optimistic | 1328 | 1339 | **351** |
| spread | in-memory | 1362 | 1324 | 367 |
| spread | redis | 1396 | 1308 | 387 |

The three experiments:

- **A**: `seatrush.holds.precheck=false`, so every request goes through the strategy's lock.
- **B**: the shipped configuration, with the precheck on and HikariCP's default of 10 database
  connections.
- **C**: B with `spring.datasource.hikari.maximum-pool-size=50`.

## A. The strategies on their own (precheck off)

### hot-seat

| Strategy | Held | Taken | Busy | Errors | Rush time (ms) | Throughput (req/s) | p50 (ms) | p95 (ms) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| pessimistic | 1 | 299 | 0 | 0 | 532 | 564 | 301 | 501 |
| optimistic | 1 | 299 | 0 | 0 | 671 | 447 | 383 | 622 |
| in-memory | 1 | 299 | 0 | 0 | 4281 | 70 | 2466 | 4094 |
| redis | 1 | 229 | 70 | 0 | 5393 | 56 | 3622 | 5090 |

### hot-row

| Strategy | Held | Taken | Busy | Errors | Rush time (ms) | Throughput (req/s) | p50 (ms) | p95 (ms) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| pessimistic | 9 | 291 | 0 | 0 | 578 | 519 | 354 | 564 |
| optimistic | 8 | 292 | 0 | 0 | 600 | 500 | 391 | 582 |
| in-memory | 8 | 292 | 0 | 0 | 4004 | 75 | 1917 | 3789 |
| redis | 8 | 292 | 0 | 0 | 1184 | 253 | 500 | 1022 |

### spread

| Strategy | Held | Taken | Busy | Errors | Rush time (ms) | Throughput (req/s) | p50 (ms) | p95 (ms) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| pessimistic | 249 | 51 | 0 | 0 | 1262 | 238 | 689 | 1224 |
| optimistic | 250 | 50 | 0 | 0 | 1328 | 226 | 777 | 1274 |
| in-memory | 250 | 50 | 0 | 0 | 1362 | 220 | 749 | 1308 |
| redis | 249 | 51 | 0 | 0 | 1396 | 215 | 765 | 1330 |

## B. With the precheck (the shipped configuration)

### hot-seat

| Strategy | Held | Taken | Busy | Errors | Rush time (ms) | Throughput (req/s) | p50 (ms) | p95 (ms) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| pessimistic | 1 | 299 | 0 | 0 | 181 | 1654 | 126 | 159 |
| optimistic | 1 | 299 | 0 | 0 | 216 | 1388 | 159 | 203 |
| in-memory | 1 | 299 | 0 | 0 | 3507 | 86 | 876 | 3177 |
| redis | 1 | 299 | 0 | 0 | 2663 | 113 | 106 | 2342 |

### hot-row

| Strategy | Held | Taken | Busy | Errors | Rush time (ms) | Throughput (req/s) | p50 (ms) | p95 (ms) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| pessimistic | 9 | 291 | 0 | 0 | 216 | 1390 | 175 | 203 |
| optimistic | 8 | 292 | 0 | 0 | 242 | 1241 | 204 | 236 |
| in-memory | 8 | 292 | 0 | 0 | 1778 | 169 | 143 | 1443 |
| redis | 8 | 292 | 0 | 0 | 840 | 357 | 121 | 537 |

### spread

| Strategy | Held | Taken | Busy | Errors | Rush time (ms) | Throughput (req/s) | p50 (ms) | p95 (ms) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| pessimistic | 249 | 51 | 0 | 0 | 1289 | 233 | 753 | 1222 |
| optimistic | 247 | 53 | 0 | 0 | 1339 | 224 | 739 | 1286 |
| in-memory | 250 | 50 | 0 | 0 | 1324 | 227 | 738 | 1268 |
| redis | 248 | 52 | 0 | 0 | 1308 | 229 | 696 | 1244 |

## What the numbers say

### 1. Under contention, what matters is how long the lock is held

**Pessimistic** wins every contended scenario. MySQL holds a seat's row lock only from the
`SELECT … FOR UPDATE` to the end of that transaction. The work before the lock runs in parallel for
all requests: taking a connection, checking the event, inserting the hold row. A request waiting
for the lock already holds its connection and is woken up by MySQL itself. Each loser's turn under
the lock is therefore tiny: read the seat, see it is HELD, roll back.

**In-memory** and **Redis** lock *around* the whole transaction. They have to, because a lock released
before the commit lets the next request read stale data (shown by a mutation test in step 9). So
every request in the queue runs a complete transaction while holding the lock: it takes a
connection and makes about four round trips to MySQL. That is roughly 12–14 ms per request on this
machine, so 300 requests in single file take about 4 s. The in-memory locks are fair, so the line
is first-come, first-served, but it is a long line. Redis adds gaps on top: a waiting request polls
again after a random pause of up to 20 ms. In experiment A, 70 of the 300 hot-seat requests gave up
after the 5 s wait timeout (*busy*).

**Optimistic** never waits. Every loser does all of its work and then fails the `@Version` check
at commit. One retry reads the seat again, sees it HELD and answers 409. That is only slightly
slower than pessimistic: the wasted work runs in parallel, not in single file.

### 2. Without contention, the strategy hardly matters, the connection pool does

In the *spread* scenario all four strategies are within about 10 % of each other. Most requests
succeed, and every success has to commit to MySQL (a log flush), so the 10 pooled connections are
the bottleneck. With 50 connections (experiment C) the same rush is **3.5× faster** for every
strategy: 367 ms instead of 1289 ms for pessimistic.

A bigger pool does not help the in-memory and Redis strategies under contention, it makes them
worse (hot-seat: 3507 → 4230 ms). Their bottleneck is the single-file line, not the pool. With more
connections, more requests get past the precheck before the first winner commits, so the line
gets longer. The pool was left at the default of 10 in `application.yaml`. The right size depends
on what the MySQL server can take, and every application instance's pool counts against MySQL's
`max_connections` (151 by default).

### 3. The precheck: do not queue for a seat that is already gone

The precheck runs before any lock is taken. It is one lock-free read that rejects requests whose
seats are already taken. It is safe: a seat it sees as taken really was taken. Seats it sees as free
are checked again under the strategy's lock. `HoldConcurrencyTest` switches it off so that the race
tests still drive every request through the locks.

It made hot-seat **2.9× faster** for pessimistic and removed every Redis wait timeout (70 busy →
0). It does nothing for *spread*, as expected. It helps the in-memory strategy least. Going by the numbers, most of the 300 requests have passed
the precheck before the first winner commits (that takes one full turn in the line), so they still
queue.
The request whose copy already won also gets the original hold back, so idempotency still holds
(`HoldPrecheckTest`).

### 4. Problems the load test found and fixed

- **Refused connections.** The very first rush of 300 users got 66 `ConnectException`s. Tomcat's
  listen backlog (`server.tomcat.accept-count`) defaults to 100. On Windows, connection attempts
  beyond the backlog are refused before Tomcat sees them. Raising it to 1000 removed every error.
- **Waiting for seats that are gone**, fixed by the precheck above.

### Which strategy to use

- **Pessimistic** (the default) is fastest under contention, simple, and correct across several
  application instances, because MySQL does the locking.
- **Optimistic** is a close second, never blocks, and suits a hall where collisions are rare.
- **Redis** is the choice when the locking itself has to live outside the database, for example to
  shield MySQL from the waiting. Its critical section would have to get shorter before it competes
  on speed. Ideas: check the seats before inserting the hold, or wake waiters with pub/sub instead of
  polling.
- **In-memory** only works with a single application instance and serialises whole transactions.
  It is good for explaining locking, not for production.

## Caveats

- Everything ran on one Windows laptop with 32 cores: the load-test client, the server, and MySQL
  8.4 and Redis 7.4 in Docker Desktop. Round trips through Docker's network and disk are slower
  than on a real server. The absolute numbers are therefore only indicative; the comparisons between
  strategies are the point.
- Three rounds per data point are enough to rank the strategies but not to quote small differences:
  pessimistic and optimistic are often within run-to-run noise of each other.
- Every rush is a single burst. A sale that stays open for minutes, with users arriving
  continuously, would need a different test.

## Reproducing

```powershell
docker compose up -d
.\mvnw.cmd -pl seatrush-server spring-boot:run          # in one terminal
.\mvnw.cmd -q -pl seatrush-loadtest package -DskipTests  # in another
java -jar seatrush-loadtest\target\seatrush-loadtest.jar --compare all
```

The report and a CSV of every rush are written to `loadtest-results/`.

- **Experiment A**: start the server after `$env:SEATRUSH_HOLDS_PRECHECK = "false"`.
- **Experiment C**: start the server after `$env:SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE = "50"`.

The load test creates the accounts `lt_user_0001` to `lt_user_0300` and one event per scenario in
the database it runs against. The raw data behind this page is in `docs/load-test-data/`.
