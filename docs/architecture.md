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

## Request flows (v1)

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

### Follow a short link

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant API as API (Spring MVC)
    participant Store as LinkStore (JdbcClient + SQLite)

    Client->>API: GET /Ab3xK9q
    API->>Store: get("Ab3xK9q")
    alt found
        Store-->>API: "https://example.com/very/long"
        API-->>Client: 302 Found, Location: https://example.com/very/long (no-store, ADR 0005)
    else not found
        Store-->>API: none
        API-->>Client: 404 Not Found
    end
```
