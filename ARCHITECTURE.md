# Architecture & Technical Design

**Target Stack**: **Java 21**, **Spring Boot 4.1.x**, **Spring AI 2.0.1**, **PostgreSQL 16** (Testcontainers), **Redis 7** (Testcontainers), **JUnit 5**, **Hibernate ORM 7.x**.

---

## 1. Overview & Design Goals

### 1.1 Problem

**Lazy-loaded relationships** (`FetchType.LAZY`) in **Hibernate** often trigger **N+1 SQL query loops**. Standard tools have two gaps:

* **SQL counters** (`datasource-proxy`, `QuickPerf`) only report raw query counts. Developers still guess between **`@EntityGraph`**, **`JOIN FETCH`**, **`@BatchSize`**, or **SQL DTO projections**.


* **External AI tools** require manual copy-pasting and cannot link the **parent Repository query (`1`)** to the **child Service loop (`N`)**.



### 1.2 How It Works

This project provides a reusable test starter (**`nplus1-ai-gate-starter`**) and demonstrates it on a wholesale electricity settlement application (**`grid-settlement-demo`**).

When a **JUnit 5 test** triggers an **N+1 loop**:

1. **Intercept**: The starter captures **SQL queries**, exact **JDBC `set*` parameter calls**, and **stack frames** across all threads.


2. **Filter**: It ignores **test setup queries**, **sequence calls** (`nextval`), **`@BatchSize` fetches**, and **pagination loops**.


3. **Correlate**: It pairs the **repeated child queries (`N`)** with the **parent Repository query (`1`)** executed in the same **Service method**.


4. **Explain**: It runs **PostgreSQL `EXPLAIN (FORMAT JSON)**` inside a **rollback-safe block** with **`enable_seqscan = off`**.


5. **Cache**: It checks **Redis** or a **local JSON file** using a **SHA-256 fingerprint**.


6. **Diagnose**: On a cache miss, it calls **Gemini (`gemini-2.5-flash-lite`)** via **Spring AI** to generate a **Java code fix**.


7. **Fail**: It fails the **JUnit 5 test** with a structured **remediation report**.



### 1.3 System Boundaries

* **Test-Only Scope**: The starter runs **only in tests** (`@SpringBootTest`, `@DataJpaTest`) and never in production runtime code.


* **No Financial Math in AI**: The **LLM** only analyzes **SQL plans and Java structure**, never settlement numbers.



### 1.4 Performance & Accuracy Targets

* **Token Budget**: Prompts use **<= 1,000 input tokens** and **<= 800 output tokens** per LLM call.


* **Cache Speed**: Cached test failures finish in **< 10ms** with **$0 API cost**.
* **Zero False Positives**: Valid **`@BatchSize`** fetches and **`Pageable` loops** pass **100% of the time**.



---

## 2. Module Structure

```text
spring-ai-nplus1-test-gate/
|-- pom.xml                                 # Parent BOM (Java 21, Spring Boot 4.1.x, Spring AI 2.0.1)
|-- nplus1-ai-gate-starter/                 # Reusable Test Library Module
|   |-- src/main/java/.../annotation/       # @EnableNPlusOneAiGate (with @ImportAutoConfiguration)
|   |-- src/main/java/.../config/           # NPlusOneGateAutoConfiguration, NPlusOneAiClientConfiguration
|   |-- src/main/java/.../interceptor/      # QueryExecutionListener & NPlusOneGateScope
|   |-- src/main/java/.../collector/        # StackTraceFilter, SourceExtractor, ExplainPlanRunner
|   |-- src/main/java/.../cache/            # FingerprintCache (Redis + Local JSON fallback)
|   |-- src/main/java/.../ai/               # Spring AI ChatClient + NPlusOneDiagnosis record
|   \-- src/main/java/.../listener/         # NPlusOneTestExecutionListener (Order = 4100)
\-- grid-settlement-demo/                   # Wholesale Energy Settlement Demo App
    |-- src/main/java/.../domain/           # MarketParticipant, BilateralContract, ContractDeclaration, etc.
    |-- src/main/java/.../repository/       # Spring Data JPA Repositories & DTO Projections
    |-- src/main/java/.../service/          # GridDailySettlementService (Unoptimized vs Optimized)
    \-- src/test/java/.../                  # Testcontainers Integration Tests (PostgreSQL 16 + Redis 7)
```

---

## 3. Core Engine (`nplus1-ai-gate-starter`)

### 3.1 SQL & Parameter Interception (`datasource-proxy`)

**Hibernate's `StatementInspector**` only exposes raw SQL with `?` placeholders and hides bound parameter values. Without bound parameters, **PostgreSQL** cannot run **`EXPLAIN`** on parameterized queries.

* **DataSource Wrapper**: A **Spring `BeanPostProcessor**` wraps the test `DataSource` with **`net.ttddyy:datasource-proxy`**.


* **Exact JDBC Parameter Capture**: The listener stores **`List<ParameterSetOperation>`** from the first execution of each query instead of a raw `List<Object>`. It filters operations to **`method.getName().startsWith("set")`** so `setNull(index, sqlType)` and `setBigDecimal` replay without **PostgreSQL type mismatch errors**.


* **Cross-Thread Storage**: `NPlusOneGateScope` stores queries in a **`ConcurrentLinkedQueue<RecordedQuery>`**. This captures SQL across **Java 21 Virtual Threads**, **`.parallelStream()`**, **`@Async`**, and web worker threads.


* **Batch Stack Marker**: Each `RecordedQuery` checks **`StackWalker`** for **`org.hibernate.engine.spi.BatchFetchQueue`** to identify valid **`@BatchSize`** queries.
* **Recursion Guard**: An **`AtomicBoolean IN_DIAGNOSTIC_MODE`** flag stops the listener from recording the gate's own **`EXPLAIN`** and **`flush()`** statements.

### 3.2 Test Lifecycle & Transaction Timing

Relying on **JUnit 5 `@ExtendWith` ordering** is fragile when annotations are reordered on a test class.

* **Deterministic Ordering**: `@EnableNPlusOneAiGate` registers `NPlusOneTestExecutionListener` (extending `AbstractTestExecutionListener`) at **`getOrder() = 4100`**. Because **`TransactionalTestExecutionListener`** runs at order **`4000`**, the gate always starts recording *after* the test transaction opens and analyzes queries *before* the transaction rolls back.


* **Crash Short-Circuit**: If **`testContext.getTestException() != null`**, the listener skips analysis immediately. This avoids running **`EXPLAIN`** on an **aborted PostgreSQL transaction (`SQLState 25P02`)** and never masks a real test failure.


* **Pre-Analysis Flush**: If a transaction is active and healthy, the listener runs **`EntityManager.flush()`** inside diagnostic mode so uncommitted rows exist in **PostgreSQL** before **`EXPLAIN`** runs.


* **Test Setup Isolation**: Developers call **`NPlusOneGateScope.reset()`** or **`NPlusOneGateScope.measure(Runnable)`** to clear **`Arrange` (setup) queries** before calling the target service method.



### 3.3 SQL Normalization & False-Positive Filtering

At the end of a passing test, the analyzer filters and groups captured `SELECT` queries:

1. **Ignore Sequences**: Drops queries matching `(?i)^\s*select\s+(nextval|currval)\b.*`.


2. **Ignore Hibernate `@BatchSize**`: Drops queries where **`isBatchFetchQueue() == true`**.
3. **Ignore Pagination**: Drops queries with `OFFSET ?` or `FETCH FIRST ? ROWS ONLY` when the bound offset parameter increases across calls.


4. **Collapse `IN` Lists**: Normalizes `IN (?, ?, ?)` to **`IN (?)`** via regex `(?i)\bIN\s*\(\s*\?(?:\s*,\s*\?)*\s*\)` so manual loop queries with varying list sizes group together.


5. **Group & Flag**: Groups by **`(normalizedSql, topServiceCallSiteFrame)`** and flags any group with **`executionCount >= threshold`** (default **`5`**).



### 3.4 Parent-Child Correlation & Safe `EXPLAIN`

When a lazy query (`N`) fires inside a **Service loop**, the **Repository method** that loaded the parent entities (`1`) has already returned and is no longer on the call stack.

* **Parent (`1`) + Child (`N`) Correlation**:
1. Extracts the **Child Call Site** (`ServiceClass.method:line`) from the repeated `N` query's stack trace.


2. Scans backward in the query log for the most recent single `SELECT` (`count == 1`) that shares the same **`ServiceClass.method`** stack frame, and extracts its **Parent `Repository` method signature**.




* **Multi-Module Source Lookup**:
* Resolves the module root via **`targetClass.getProtectionDomain().getCodeSource().getLocation()`** (navigating from `target/classes` to `src/main/java`) instead of `user.dir` so **Maven reactor builds** find source files reliably.


* Falls back to **Java reflection** if `.java` source files are unavailable.




* **Rollback-Safe, Pruned `EXPLAIN**`:
1. Acquires the connection via **`DataSourceUtils.getConnection(ds)`** and always releases it in `finally` via **`DataSourceUtils.releaseConnection(conn, ds)`** to prevent **HikariCP connection leaks**.


2. If `conn.getAutoCommit()` is `true` (non-transactional test), temporarily sets **`conn.setAutoCommit(false)`**; otherwise creates a **JDBC `Savepoint**` (`conn.setSavepoint("NPLUS1_EXPLAIN")`).


3. Executes **`SET LOCAL enable_seqscan = off;`** so **PostgreSQL** reveals **Index Scans** even on small 20-row test tables that fit inside a single 8KB heap page.


4. Replays the captured `set*` **`ParameterSetOperation`** calls on `EXPLAIN (FORMAT JSON) <sql>`, then rolls back the savepoint (or temporary transaction) and restores `autoCommit`.


5. Strips the JSON plan down to **`Node Type`**, **`Relation Name`**, **`Index Name`**, and **`Filter`** to stay under the **1,000-token budget**.





### 3.5 SHA-256 Fingerprint Cache

* **Key Formula**: `SHA-256(normalizedSql + serviceMethodSignature + repositorySignature + entitySignature)`.


* **Storage**: Checks **Redis** (if configured) or local **`.nplus1-ai-cache.json`**. Including `serviceMethodSignature` ensures two different service loops on the same entity never return the wrong cached fix.



### 3.6 Slice Auto-Configuration & Spring AI Integration

* **Slice Compatibility**: `@DataJpaTest` ignores standard `AutoConfiguration.imports`. `@EnableNPlusOneAiGate` binds its beans directly using **`@ImportAutoConfiguration`**:



```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ImportAutoConfiguration({
    NPlusOneGateAutoConfiguration.class,
    NPlusOneAiClientConfiguration.class
})
@TestExecutionListeners(
    value = NPlusOneTestExecutionListener.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
public @interface EnableNPlusOneAiGate {
    int threshold() default 5;
    String basePackage() default "";
}

```

* **Spring AI Settings (`application-test.yml`)**: Uses **`gemini-2.5-flash-lite`** (`temperature: 0.1`, `max-output-tokens: 800`) with **`StructuredOutputValidationAdvisor`** (max 2 retries). `NPlusOneDiagnosis` uses `com.fasterxml.jackson.annotation.*` for **Jackson 2 / Jackson 3** compatibility in **Spring Boot 4.1**.


* **Offline Fallback**: If **`GEMINI_API_KEY`** is unset or returns HTTP 429/5xx, the gate fails the JUnit test deterministically with the **query count**, **correlated stack frames**, and **pruned `EXPLAIN` plan**.



### 3.7 Terminal Failure Report

When a test violates the threshold, `AssertionError` outputs:

```text
[N+1 AI GATE FAILURE] Detected 20 repeated SELECT queries (Threshold: 5)
  Parent Query (1) : ContractDeclarationRepository.findConfirmedForParticipantSummary(LocalDate)
  Child Loop   (N) : GridDailySettlementService.buildParticipantSummary(GridDailySettlementService.java:54)
  Offending SQL    : SELECT ... FROM bilateral_contract WHERE id = ?
  PG Explain Plan  : Index Scan on bilateral_contract using bilateral_contract_pkey
  AI Diagnosis     : Lazy @ManyToOne traversal inside loop.
  Recommended Fix  : Add @EntityGraph(attributePaths = {"bilateralContract", "bilateralContract.buyer"})
                     to ContractDeclarationRepository.findConfirmedForParticipantSummary(LocalDate).

```

---

## 4. Reference Domain (`grid-settlement-demo`)

### 4.1 Domain Model & Schema

Wholesale electricity markets settle energy across **288 five-minute dispatch intervals** per 24-hour trading day:

1. **`MarketParticipant`**: Generators (sellers) and distribution utilities (buyers) (`id`, `participantCode` [unique], `shortName`, `role`).


2. **`BilateralContract`**: Supply contracts (`id`, `contractCode` [unique], `seller` [`@ManyToOne(LAZY)`], `buyer` [`@ManyToOne(LAZY)`]).


3. **`ContractDeclaration`**: Daily header (`id`, `tradingDate`, `status`, `bilateralContract` [`@ManyToOne(LAZY)`], `intervals` [`@OneToMany(LAZY, mappedBy = "declaration")` -> 288 rows]).


4. **`IntervalQuantity`**: 5-minute interval data (`id`, `dispatchInterval` [`1..288`], `gridNodeCode`, `declaredMwh` [`BigDecimal(18, 6)`], `declaration` [`@ManyToOne(LAZY)`]) with a **composite unique index** on `(declaration_id, dispatch_interval, grid_node_code)`.


5. **`MeterReadingHeader` & `MeterIntervalReading**`: High-frequency telemetry ingested via **JDBC batching**.



### 4.2 Verification Scenarios

* **Scenario A (`@ManyToOne` Chain -> `@EntityGraph`)**:
* **Entry Point**: `ContractDeclarationRepository.findConfirmedForParticipantSummary(LocalDate date)`.


* **Bug**: Loads 20 declarations and calls `declaration.getBilateralContract().getBuyer().getShortName()` in a loop.


* **Expected AI Fix**: Add **`@EntityGraph(attributePaths = {"bilateralContract", "bilateralContract.buyer"})`** to `findConfirmedForParticipantSummary`.




* **Scenario B (`@OneToMany` 288-Row Aggregation -> JPQL `SUM/GROUP BY` DTO)**:
* **Entry Point**: `ContractDeclarationRepository.findConfirmedForDailyVolumeAudit(LocalDate date)`.
* **Bug**: Loads 20 declarations and streams `declaration.getIntervals()` (288 child rows each) in Java to sum `declaredMwh`.


* **Expected AI Fix**: Replace collection loading with a **JPQL DTO projection** (`SELECT d.id, SUM(i.declaredMwh) ... GROUP BY d.id`) so **5,760 child entities** are never loaded into heap memory.




* **Scenario C (Optimized Baseline & `@BatchSize` Pass)**:
* **Entry Point**: Runs the refactored methods and a **`@BatchSize(size = 20)`** test case to verify that the gate passes with **zero false positives** and **zero LLM calls**.

This **Section 5: Key Engineering Trade-offs** table is ready to paste directly at the bottom of your **`ARCHITECTURE.md`** file.

---

## 5. Key Engineering Trade-offs

| Decision Area | Chosen Approach | Rejected Alternative | Why We Chose It & Trade-off |
| --- | --- | --- | --- |
| **SQL Interception** | **`datasource-proxy`** (`BeanPostProcessor`)

 | Hibernate **`StatementInspector`**<br> | **Why**: `StatementInspector` only exposes `?` placeholders, hiding bound values needed for **PostgreSQL `EXPLAIN**`.

<br>

<br>**Trade-off**: Adds an extra proxy wrapper around the test `DataSource`.

 |
| **Lifecycle Hook** | Spring **`TestExecutionListener` (`Order = 4100`)**<br> | JUnit 5 **`AfterTestExecutionCallback`**<br> | **Why**: Guarantees the gate runs *before* **`TransactionalTestExecutionListener` (`4000`)** rolls back the transaction, regardless of annotation order.

<br>

<br>**Trade-off**: Requires **`spring-test`** context rather than pure JUnit 5. |
| **Parameter Replay** | **`ParameterSetOperation`** reflective replay

 | Flat **`List<Object>`** + `ps.setObject()`<br> | **Why**: Preserves exact **`setNull(idx, sqlType)`** and **`setBigDecimal`** calls so **PostgreSQL** never fails with type errors.

<br>

<br>**Trade-off**: Uses reflection (`Method.invoke`) during diagnostic runs.

 |
| **Thread Context** | Test-scoped **`ConcurrentLinkedQueue`**<br> | **`ThreadLocal`** storage

 | **Why**: Captures queries spawned on **Java 21 Virtual Threads**, **`.parallelStream()`**, **`@Async`**, and web server threads.

<br>

<br>**Trade-off**: Cannot run tests in parallel inside the same JVM fork without context isolation. |
| **Small-Table `EXPLAIN**` | **JDBC `Savepoint**` + **`enable_seqscan = off`**<br> | Unscoped **`EXPLAIN (FORMAT JSON)`**<br> | **Why**: Prevents **PostgreSQL** from choosing **`Seq Scan`** on tiny 20-row test tables (which tricks the **LLM** into blaming missing indexes) and prevents transaction abortion (`25P02`).

<br>

<br>**Trade-off**: Adds savepoint overhead on failed tests.

 |
| **Slice Wiring** | **`@ImportAutoConfiguration`** on **`@EnableNPlusOneAiGate`**<br> | Global **`AutoConfiguration.imports`**<br> | **Why**: Works inside **`@DataJpaTest`** slices without breaking **`@WebMvcTest`** tests that have no `DataSource`.

<br>

<br>**Trade-off**: Developers must annotate test classes with **`@EnableNPlusOneAiGate`**.

 |
