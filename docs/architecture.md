# Architecture

## The two planes

The repository holds two separate systems with **separate entry points**. The **product plane** is the Java service that end users use. The **delivery plane** is the development-time orchestrator (`orchestrator/`, planned for Release 2) that turns requests into reviewed pull requests. The orchestrator never deploys and never connects to a running service. Its validation stages start temporary instances on their own port and data directory. A change reaches the product only when a human merges a PR ([ADR 0007](adr/0007-delivery-orchestrator.md)).

```mermaid
flowchart LR
    subgraph DEV["Delivery plane: development time (orchestrator/, Python), on demand"]
        direction TB
        CLI["Engineer runs<br/>orchestrate start (issue number)"] --> ORCH["Orchestrator<br/>LangGraph stages and approvals<br/>Agent SDK steps"]
        ORCH --> WT["Git worktree<br/>code, tests, docs"]
        WT --> TMP["Temporary service instance<br/>own port, temporary data dir<br/>mvn verify, e2e, load smoke test"]
    end

    ORCH <--> LLM["Claude API"]
    ORCH -->|"branches, PRs, Issue comments, run log"| GH["GitHub<br/>Issues, PRs, CI, delivery board"]
    GH -->|"a human reviews and merges"| MAIN["main"]

    subgraph RUN["Product plane: runtime (Java service), always on"]
        SVC["Shortener service<br/>java -jar, scripts/local.sh, container"]
    end

    MAIN -->|"build and deploy: a separate step, not the orchestrator"| SVC
    USERS(["End users"]) --> SVC
    ORCH -.-x|"never connects"| SVC
```

The rest of this document describes the product plane. The orchestrator's stage graph is in [ADR 0008](adr/0008-orchestrator-stage-graph-and-governance.md).

## Request flows

### Create a short link

The code generator takes **no input**; it never sees the long URL (ADR 0003). The database's uniqueness constraint is the final guarantee against duplicate codes. Shortening the same URL twice creates two independent links.

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant API as API (Spring MVC)
    participant Gen as ShortCodeGenerator
    participant Store as LinkStore (JdbcClient + SQLite)

    Client->>API: POST /links {"url": "https://example.com/very/long"}
    API->>API: validate(url)
    alt invalid URL
        API-->>Client: 422 Unprocessable Entity
    else valid URL
        loop until saved or max attempts reached
            API->>Gen: next()
            Gen-->>API: "Ab3xK9q" (7 random base62 chars)
            API->>Store: save(code, url)
            alt code already taken
                Store-->>API: conflict, so retry
            else saved
                Store-->>API: ok
            end
        end
        alt saved
            API-->>Client: 201 {"short_url": "http://host/Ab3xK9q"}
        else max attempts exhausted
            API-->>Client: 503 Service Unavailable
        end
    end
```

### Follow a short link (the Redirect)

The Redirect handler (`LinkController.followLink`) is the single point where Clicks are produced (ADR 0005). Since Release 2 ([spec 0003](specs/0003-clickstream.md)) a successful `GET` Redirect sends its `302` first, then builds a **Click** and hands it to the **Click Recorder**, which returns at once and never throws ([ADR 0012](adr/0012-clicks-recorded-asynchronously.md)). The `Click Classifier` reduces the raw `Referer` and `User-Agent` to a Referrer Host, an Agent Category and a Device Class in memory; the raw values are never stored or logged, and the IP address is not read at all ([ADR 0013](adr/0013-clicks-store-minimal-non-personal-data.md)). A `404` and a `HEAD` request record nothing. The response is byte-for-byte what Release 1 sent.

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant API as Redirect handler (LinkController)
    participant Store as LinkStore (JdbcClient + SQLite)
    participant Cls as ClickClassifier
    participant Rec as ClickRecorder (queued)

    Client->>API: GET /Ab3xK9q (Referer, User-Agent)
    API->>Store: findLongUrl("Ab3xK9q")
    alt found
        Store-->>API: "https://example.com/very/long"
        API-->>Client: 302 Found, Location: https://example.com/very/long (no-store, ADR 0005)
        opt GET only (HEAD records nothing)
            API->>Cls: classify(referer, userAgent)
            Cls-->>API: Referrer Host, Agent Category, Device Class
            API->>Rec: record(Click: Short Code, time (UTC), attributes)
            Note over API,Rec: returns at once and never throws,<br/>a failure here costs only this Click (logged, counted)
        end
    else not found
        Store-->>API: none
        API-->>Client: 404 Not Found (no Click)
    end
```

### The Click flow (Clickstream)

The `QueuedClickRecorder` keeps Clicks in a **bounded in-memory queue** (`CLICK_QUEUE_CAPACITY`) and offers each one without waiting. **One background writer** takes up to `CLICK_BATCH_SIZE` Clicks at a time, waiting at most `CLICK_FLUSH_INTERVAL` for a batch to fill, and saves each batch with one **Click Store** call in one transaction. `JdbcClickStore` is the only code that touches the `clicks` table (Flyway `V2__create_clicks.sql`, indexed by Short Code and time), as the Link Store is for `links` (ADR 0002). Loss is bounded and counted, never an error: a Click that meets a full queue, and every Click in a batch that fails to save, are **dropped and counted**, with no retry. Drops are logged as one warning carrying counts only, at most once per flush interval. The recorder keeps `stats()` (recorded, dropped, pending) for R12 to publish as metrics (ADR 0015).

On a normal shutdown the recorder, a Spring `SmartLifecycle` bean, stops **after** the web server has stopped taking requests, so Redirects served while shutting down are still recorded. It **flushes** the queue for up to `CLICK_SHUTDOWN_TIMEOUT`; Clicks still unsaved then are dropped, counted and logged. A crash, unlike a normal shutdown, loses the Clicks still queued.

```mermaid
flowchart LR
    R["Redirect handler<br/>(after the 302 is sent)"] -->|"record(Click), offer: never waits"| Q[["Click Recorder queue<br/>bounded: CLICK_QUEUE_CAPACITY"]]
    Q -->|"queue full"| D["dropped count<br/>+ warning (counts only,<br/>at most once per flush interval)"]
    Q -->|"take up to CLICK_BATCH_SIZE,<br/>wait at most CLICK_FLUSH_INTERVAL"| W["Batch writer<br/>one background thread"]
    W -->|"one call per batch,<br/>one transaction"| CS["Click Store<br/>(JdbcClickStore)"]
    CS --> CT[("clicks table<br/>index (short_code, clicked_at)")]
    W -->|"batch failed to save:<br/>all its Clicks, no retry"| D
    W -->|"saved"| ST["stats(): recorded, dropped, pending<br/>(metrics in R12)"]
    D --> ST
    SD["Normal shutdown<br/>after the web server stops"] -->|"flush within<br/>CLICK_SHUTDOWN_TIMEOUT"| W
    SD -->|"still unsaved at the timeout"| D
    CT -.->|"read path: list a Short Code's Clicks, oldest first"| S["R2 stats API (later)"]
```

### SQLite concurrency (ADR 0021)

Two writers now share SQLite's single write lock: Link creation and the Click writer. [ADR 0021](adr/0021-sqlite-wal-busy-timeout-and-small-connection-pool.md) sets, on every pooled connection through the SQLite JDBC driver's connection properties in `application.properties`:

| Setting | Value | Effect |
|---|---|---|
| `journal_mode` | `WAL` | Readers never wait for the writer, so a Redirect lookup completes while a Click batch is being written |
| `busy_timeout` | `5000` ms | The two writers queue for the write lock instead of failing |
| Hikari `maximum-pool-size` | `4` (was 1) | A Redirect lookup gets its own connection while the Click writer holds one |

```mermaid
flowchart LR
    subgraph APP["Shortener service"]
        RL["Redirect lookups<br/>(read)"]
        LC["Link creation<br/>(write)"]
        CW["Click writer<br/>(write, batches)"]
    end
    RL & LC & CW --> POOL["Connection pool<br/>4 connections, each WAL + busy_timeout 5000 ms"]
    POOL --> DB[("links.db")]
    POOL --> WAL[("links.db-wal")]
    POOL --> SHM[("links.db-shm")]
```

The database is therefore **three files**: `links.db`, `links.db-wal` and `links.db-shm`. Back up, move or delete them together, and keep them on a local file system (never NFS or SMB); see [onboarding](onboarding.md#3-run-it).
