# Architecture

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
