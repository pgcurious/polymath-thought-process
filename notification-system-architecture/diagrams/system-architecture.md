# System Architecture Diagrams

## 1. High-Level System Architecture

```mermaid
graph TB
    subgraph "Client Applications"
        A[Mobile Apps]
        B[Web Apps]
        C[Backend Services]
    end

    subgraph "API Gateway Layer"
        D[Load Balancer]
        E[API Gateway]
        F[Rate Limiter]
        G[Auth Service]
    end

    subgraph "Ingestion Layer"
        H[Validation Service]
        I[Deduplication Service]
        J[Batching Service]
    end

    subgraph "Message Queue"
        K[(Kafka)]
        K1[Priority Queue: Critical]
        K2[Priority Queue: High]
        K3[Priority Queue: Medium]
        K4[Priority Queue: Low]
    end

    subgraph "Routing Engine"
        L[Preference Evaluator]
        M[Channel Selector]
        N[Template Renderer]
        O[A/B Testing Service]
    end

    subgraph "Delivery Layer"
        P[APNs Client]
        Q[FCM Client]
        R[Email Service]
        S[SMS Gateway]
    end

    subgraph "Data Stores"
        T[(User DB)]
        U[(Preference Cache)]
        V[(Analytics DB)]
        W[(Notification Archive)]
    end

    subgraph "Observability"
        X[Metrics]
        Y[Distributed Tracing]
        Z[Logging]
    end

    A --> D
    B --> D
    C --> D
    D --> E
    E --> F
    E --> G
    F --> H
    H --> I
    I --> J
    J --> K
    K --> K1
    K --> K2
    K --> K3
    K --> K4
    K1 --> L
    K2 --> L
    K3 --> L
    K4 --> L
    L --> M
    M --> N
    N --> O
    O --> P
    O --> Q
    O --> R
    O --> S

    L -.-> U
    M -.-> T
    N -.-> T

    P --> X
    Q --> X
    R --> X
    S --> X

    H --> Y
    L --> Y
    O --> Y

    J --> W
    O --> V
```

## 2. Notification Lifecycle

```mermaid
stateDiagram-v2
    [*] --> Created
    Created --> Validated
    Validated --> Invalid
    Invalid --> Rejected
    Rejected --> [*]

    Validated --> Queued
    Queued --> Expired
    Expired --> Discarded
    Discarded --> [*]

    Queued --> Routed
    Routed --> Filtered
    Filtered --> Suppressed
    Suppressed --> [*]

    Routed --> Dispatched
    Dispatched --> DeliveryFailed
    DeliveryFailed --> Retry
    Retry --> Dispatched
    DeliveryFailed --> Abandoned
    Abandoned --> [*]

    Dispatched --> Delivered
    Delivered --> Displayed
    Delivered --> Clicked
    Delivered --> Ignored
    Delivered --> Read

    Displayed --> [*]
    Clicked --> [*]
    Ignored --> [*]
    Read --> [*]
```

## 3. Write Path Detail

```mermaid
sequenceDiagram
    participant Client
    participant API
    participant RateLimiter
    participant Validator
    participant IdempotencyCache
    participant BatchBuffer
    participant Kafka

    Client->>API: POST /notifications
    API->>RateLimiter: Check rate limit

    alt Rate limit exceeded
        RateLimiter-->>API: 429 Too Many Requests
        API-->>Client: Retry after 1s
    else Limit OK
        RateLimiter-->>API: Proceed
        API->>IdempotencyCache: Check request_id

        alt Duplicate request
            IdempotencyCache-->>API: Return cached response
            API-->>Client: 200 OK (cached)
        else New request
            API->>Validator: Validate request

            alt Invalid
                Validator-->>API: ValidationError
                API-->>Client: 400 Bad Request
            else Valid
                Validator-->>API: OK
                API->>BatchBuffer: Add to buffer
                BatchBuffer-->>API: Accepted
                API->>IdempotencyCache: Cache response
                API-->>Client: 202 Accepted

                Note over BatchBuffer: Accumulate for 100ms or 1000 items

                BatchBuffer->>Kafka: Publish batch
                Kafka-->>BatchBuffer: Acknowledged
            end
        end
    end
```

## 4. Routing Engine Flow

```mermaid
graph LR
    subgraph "Input"
        A[Notification from Kafka]
    end

    subgraph "Stage 1: Preference Check"
        B[Load User Preferences]
        C{Allowed by Preferences?}
    end

    subgraph "Stage 2: Channel Selection"
        D{User Online?}
        E[Select Real-Time Channels]
        F[Select Async Channels]
    end

    subgraph "Stage 3: Template Rendering"
        G[Get User Language]
        H[Render Template]
        I[Format for Channel]
    end

    subgraph "Stage 4: Optimization"
        J{In Quiet Hours?}
        K[Schedule for Later]
        L{Batch with Similar?}
        M[Coalesce Notifications]
    end

    subgraph "Stage 5: Dispatch"
        N[Add to Delivery Queue]
    end

    subgraph "Output"
        O[Filter/Suppressed]
        P[To Delivery Layer]
    end

    A --> B
    B --> C
    C -->|No| O
    C -->|Yes| D
    D -->|Yes| E
    D -->|No| F
    E --> G
    F --> G
    G --> H
    H --> I
    I --> J
    J -->|Yes| K
    J -->|No| L
    K --> N
    L -->|Yes| M
    L -->|No| N
    M --> N
    N --> P
```

## 5. Delivery Layer with Circuit Breakers

```mermaid
graph TB
    subgraph "Delivery Workers"
        W1[Worker 1]
        W2[Worker 2]
        W3[Worker 3]
    end

    subgraph "Circuit Breakers"
        CB1[APNs Circuit Breaker]
        CB2[FCM Circuit Breaker]
        CB3[Email Circuit Breaker]
        CB4[SMS Circuit Breaker]
    end

    subgraph "External Providers"
        P1[APNs]
        P2[FCM]
        P3[SMTP Server]
        P4[Twilio SMS]
    end

    subgraph "Fallback Handling"
        DLQ[Dead Letter Queue]
        Retry[Retry Queue]
    end

    W1 --> CB1
    W1 --> CB2
    W2 --> CB3
    W3 --> CB4

    CB1 -->|CLOSED| P1
    CB1 -->|OPEN| DLQ
    CB2 -->|CLOSED| P2
    CB2 -->|OPEN| DLQ
    CB3 -->|CLOSED| P3
    CB3 -->|HALF_OPEN| Retry
    CB4 -->|CLOSED| P4

    P1 -.->|Success| CB1
    P1 -.->|Failure| CB1
    P2 -.->|Success| CB2
    P2 -.->|Failure| CB2
    P3 -.->|Success| CB3
    P3 -.->|Failure| CB3
    P4 -.->|Success| CB4
    P4 -.->|Failure| CB4
```

## 6. Distributed Tracing Example

```mermaid
gantt
    title Notification Trace (452ms total)
    dateFormat SSS
    axisFormat %L ms

    section API Layer
    Validate Request           :a1, 000, 12ms
    Check Preferences          :a2, 012, 45ms

    section Routing
    Template Render            :b1, 057, 23ms
    Publish to Kafka           :b2, 080, 8ms

    section Delivery
    Deliver Push               :c1, 088, 364ms
    APNs Send                  :c2, 088, 350ms
    Record Delivery            :c3, 438, 14ms
```

## 7. Data Flow Architecture

```mermaid
graph LR
    subgraph "Hot Path (Real-Time)"
        A[Ingestion] --> B[Validation]
        B --> C[Routing]
        C --> D[Delivery]
        D --> E[Metrics]
    end

    subgraph "Cold Path (Analytics)"
        F[Event Stream]
        G[Analytics Pipeline]
        H[Data Warehouse]
        I[BI Tools]
    end

    subgraph "Storage Tiers"
        J[(Hot: Last 7 days<br/>Redis + Cassandra)]
        K[(Warm: 7-90 days<br/>Cassandra)]
        L[(Cold: >90 days<br/>S3/GCS)]
    end

    B --> F
    C --> F
    D --> F

    F --> G
    G --> H
    H --> I

    D --> J
    J -.->|Age out| K
    K -.->|Archive| L
```

## 8. Scaling Strategy

```mermaid
graph TB
    subgraph "Regional Deployment"
        R1[US-East]
        R2[US-West]
        R3[EU-West]
        R4[Asia-Pacific]
    end

    subgraph "Each Region Contains"
        direction TB
        API[API Servers<br/>Auto-scaling: 10-100]
        Kafka[Kafka Cluster<br/>Fixed: 12 brokers]
        Workers[Routing Workers<br/>Auto-scaling: 50-500]
        Cache[Redis Cluster<br/>Fixed: 6 nodes]
        DB[(Cassandra<br/>Fixed: 18 nodes)]
    end

    subgraph "Global Services"
        GLB[Global Load Balancer]
        GDB[(Global User DB)]
        Analytics[Analytics Pipeline]
    end

    GLB --> R1
    GLB --> R2
    GLB --> R3
    GLB --> R4

    R1 --> API
    API --> Kafka
    Kafka --> Workers
    Workers --> Cache
    Workers --> DB

    DB -.->|Replicate| GDB
    Workers --> Analytics
```

## Architecture Notes

### Scaling Numbers

**Ingestion Layer**:
- API servers: 100 instances × 10K req/sec = 1M req/sec
- Kafka: 12 brokers × 100K msg/sec = 1.2M msg/sec

**Routing Engine**:
- Workers: 500 instances × 2K routes/sec = 1M routes/sec
- Preference cache: 10M users × 25 bytes = 250 MB per region

**Delivery Layer**:
- APNs: 10 certificates × 10K req/sec = 100K req/sec
- FCM: 10 accounts × 15K req/sec = 150K req/sec

**Storage**:
- Hot tier: 700B notifications × 1KB = 700 TB
- Warm tier: 8.3T notifications × 1KB = 8.3 PB
- Cold tier: Unbounded (S3 Glacier)

### Reliability

- Multi-region: 4 regions, any 2 can serve full traffic
- Circuit breakers: Prevent cascade failures
- Rate limiting: Per-service, per-user, per-provider
- Retries: Exponential backoff with jitter
- Dead letter queues: Isolate permanent failures

### Observability

- Metrics: Prometheus (1-minute retention) → Thanos (long-term)
- Tracing: OpenTelemetry → Jaeger (7-day retention)
- Logs: Structured JSON → Elasticsearch (30-day retention)
- Alerts: PagerDuty for critical, Slack for warnings
