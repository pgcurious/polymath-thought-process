# Appendix: Tools for the Journey

## A. Capacity Planning Calculations

### A.1 Throughput Requirements

**Given**:
- 10 billion users
- 10 notifications per user per day (average)
- Peak traffic: 5× average

**Calculations**:

```
Total notifications/day = 10B users × 10 notifs/user = 100B notifications/day

Average rate = 100B / (24 × 3600) = 1,157,407 notifications/second
             ≈ 1.16M notifications/second

Peak rate = 1.16M × 5 = 5.8M notifications/second
```

### A.2 Storage Requirements

**Hot tier** (last 7 days):

```
Daily notifications: 100B
7-day retention: 100B × 7 = 700B notifications

Average notification size: 1 KB
Storage needed: 700B × 1 KB = 700 TB

With 3× replication: 700 TB × 3 = 2.1 PB
```

**Warm tier** (8-90 days):

```
Retention period: 90 - 7 = 83 days
Notifications: 100B × 83 = 8.3 trillion

Storage: 8.3T × 1 KB = 8.3 PB
With replication: 8.3 PB × 3 = 24.9 PB
```

**Total storage**: 2.1 PB (hot) + 24.9 PB (warm) = **27 PB**

### A.3 Bandwidth Requirements

**Ingestion**:

```
Peak: 5.8M notifications/sec
Payload size: 2 KB (with metadata)

Ingress bandwidth: 5.8M × 2 KB = 11.6 GB/sec = 92.8 Gbps
```

**Delivery**:

```
Push notifications: 70% × 5.8M = 4.06M/sec
Average push payload: 500 bytes

Egress bandwidth: 4.06M × 500 bytes = 2.03 GB/sec = 16.2 Gbps
```

**Total bandwidth**: ~109 Gbps (11 × 10 Gbps links with overhead)

### A.4 Database Sizing

**User preferences** (cached):

```
Active users (online): 10B × 40% = 4B users
Preference bitmap size: 25 bytes per user

Memory needed: 4B × 25 bytes = 100 GB
With overhead: 100 GB × 2 = 200 GB

Redis instances: 200 GB / 50 GB per instance = 4 instances
(Using r6g.4xlarge with 128 GB RAM)
```

**Cassandra cluster** (notification storage):

```
Hot tier: 700B notifications × 1 KB = 700 TB
Replication factor: 3
Total: 2.1 PB

Node size: 2 TB per node (SSD)
Nodes needed: 2.1 PB / 2 TB = 1,050 nodes

Organized as: 3 datacenters × 350 nodes each
```

### A.5 Compute Requirements

**API servers**:

```
Target: 1M requests/sec
Per-server capacity: 10K req/sec (based on benchmarks)

Servers needed: 1M / 10K = 100 servers
With 2× headroom: 200 servers

Instance type: c6g.4xlarge (16 vCPUs, 32 GB RAM)
Cost: $0.544/hour × 200 = $108.80/hour = $78,336/month
```

**Routing workers**:

```
Target: 1M routes/sec
Per-worker capacity: 2K routes/sec

Workers needed: 1M / 2K = 500 workers
With headroom: 750 workers

Instance type: c6g.2xlarge (8 vCPUs, 16 GB RAM)
Cost: $0.272/hour × 750 = $204/hour = $146,880/month
```

### A.6 Cost Estimation (AWS)

**Compute** (monthly):

```
API servers:      $78,336
Routing workers:  $146,880
Delivery workers: $50,000 (estimated)
Kafka brokers:    $30,000
Total compute:    $305,216/month
```

**Storage** (monthly):

```
EBS (SSD): 2.1 PB × $0.10/GB = $210,000
S3 (archive): 100 PB × $0.004/GB = $400,000
Total storage: $610,000/month
```

**Network** (monthly):

```
Data transfer out: 5 PB/month × $0.05/GB = $250,000
Total network: $250,000/month
```

**Grand total**: ~$1.17 million/month = **$14 million/year**

*Note: With committed use discounts and reserved instances, actual cost would be ~$10 million/year*

---

## B. Interview Questions by Chapter

### Chapter 1: The Impossible Brief

**Q1**: Estimate how many push notifications are sent globally per day. Show your work.

**Q2**: You're told to design a system for "billions of users." What clarifying questions do you ask?

**Q3**: Compare the trade-offs: Would you rather have 99.9% delivery rate with 100ms latency, or 99.99% delivery rate with 500ms latency? Why?

### Chapter 2: First Principles

**Q4**: Design a state machine for email notifications (with spam filtering, bounces, and read receipts). How many states? What transitions?

**Q5**: A notification gets "stuck" in DISPATCHED state for 10 minutes. Walk through your debugging process.

**Q6**: Should notification IDs be sequential, UUIDs, or something else? Defend your choice.

### Chapter 3: The Data Model

**Q7**: You need to query "all notifications sent to user X in the last 7 days." Design the optimal data model and query.

**Q8**: A user has 1 million unread notifications. What happens when they open the app? How do you prevent the app from crashing?

**Q9**: Design a priority system that handles: security alerts, payment reminders, social notifications, and marketing. How do you ensure security always wins?

### Chapter 4: The Write Path

**Q10**: Your ingestion API receives 100K duplicate requests (same request_id) in 10 seconds. What happens? How do you handle it?

**Q11**: Implement a distributed rate limiter using Redis. Handle edge cases (Redis down, network partition).

**Q12**: You need to ingest 10M notifications in 1 second (traffic spike). Design the batching strategy.

### Chapter 5: The Routing Engine

**Q13**: User preferences allow "message" notifications via push. But they're in quiet hours. The message is from their boss. What do you send? How do you decide?

**Q14**: You have 100 notification templates in 50 languages. How do you organize and cache them?

**Q15**: Design an A/B test: 50% get notifications immediately, 50% get batched notifications (5-minute window). How do you measure which is better?

### Chapter 6: The Delivery Layer

**Q16**: APNs is down (circuit breaker open). You have 1M notifications queued. What do you do?

**Q17**: You send a push notification to device token X. APNs returns "410 Unregistered." What's your handling strategy?

**Q18**: Design a retry strategy for email delivery (SMTP). Consider: temporary failures (421), permanent failures (550), rate limiting (451).

### Chapter 7: Observability

**Q19**: You're paged: "Delivery latency p99 is 5 seconds (normally 200ms)." Walk through your investigation using metrics, logs, and traces.

**Q20**: Design a dashboard for a notification system. What are the top 5 metrics you'd show?

**Q21**: A user reports they didn't receive a notification. Describe the "autopsy" process.

### Chapter 8: Edge Cases

**Q22**: User travels from New York to Tokyo. They have quiet hours: 10 PM - 8 AM. What time do you respect?

**Q23**: A bug causes your system to send the same notification 1 million times to one user in 5 minutes. How do you detect and stop it?

**Q24**: GDPR requires you to delete a user's data. They have 10 million notifications. What's your strategy?

### Chapter 9: Evolution

**Q25**: You need to add a new field to a table with 10 billion rows. Describe the migration process with zero downtime.

**Q26**: Design a plugin architecture for delivery channels. How do you add Slack without modifying core code?

**Q27**: You want to A/B test a new routing algorithm. 1% of users get the new version. Implement the rollout mechanism.

### Chapter 10: The Human Element

**Q28**: Your PM says: "Increase notification volume by 50% to hit engagement targets." What do you say?

**Q29**: Design a notification preference system that balances business needs with user well-being.

**Q30**: A competitor sends 2× more notifications than you. Your metrics are declining. What do you do?

---

## C. Glossary of Terms

**APNs** (Apple Push Notification service): Apple's service for delivering push notifications to iOS devices.

**Backpressure**: Mechanism to slow down producers when consumers can't keep up.

**CAP Theorem**: In distributed systems, you can have at most 2 of: Consistency, Availability, Partition tolerance.

**Circuit Breaker**: Design pattern that detects failures and prevents cascading failures by temporarily rejecting requests.

**Dead Letter Queue (DLQ)**: Queue for messages that can't be processed (permanent failures).

**Denormalization**: Storing redundant data to optimize read performance at the cost of storage and write complexity.

**Event Sourcing**: Storing state as a sequence of immutable events rather than current state.

**Exactly-Once Delivery**: Guarantee that a message is delivered exactly one time (hard to achieve).

**FCM** (Firebase Cloud Messaging): Google's service for delivering push notifications to Android devices.

**Idempotency**: Property where an operation can be applied multiple times with the same result as applying it once.

**Load Shedding**: Intentionally dropping low-priority requests when the system is overloaded.

**Partition Key**: Key used to distribute data across multiple nodes in a distributed database.

**p99 Latency**: 99th percentile latency (99% of requests are faster than this value).

**Rate Limiting**: Controlling the rate of requests to prevent overload.

**Replication Factor**: Number of copies of data maintained for redundancy.

**Sharding**: Distributing data across multiple databases to scale horizontally.

**SLA** (Service Level Agreement): Commitment to maintain a certain level of service (e.g., 99.9% uptime).

**Throughput**: Number of operations per unit time (e.g., 1M requests/second).

**Time-to-Live (TTL)**: Duration after which data expires and is deleted.

**Token Bucket**: Rate limiting algorithm that allows bursts while maintaining long-term rate.

**Trade-off**: Giving up one quality to gain another (e.g., consistency vs. availability).

---

## D. Further Reading

### Distributed Systems

**Books**:
- *Designing Data-Intensive Applications* by Martin Kleppmann (essential reading)
- *Database Internals* by Alex Petrov (deep dive into storage engines)
- *The Art of Scalability* by Martin Abbott & Michael Fisher
- *Site Reliability Engineering* by Google (free online)

**Papers**:
- "Dynamo: Amazon's Highly Available Key-value Store" (eventual consistency)
- "The Google File System" (distributed storage)
- "MapReduce: Simplified Data Processing on Large Clusters"

### Message Queues & Event Streaming

**Resources**:
- Kafka: The Definitive Guide by Neha Narkhede et al.
- "Designing Event-Driven Systems" by Ben Stopford (free ebook)
- RabbitMQ documentation (AMQP protocol)

### Notification Systems (Industry)

**Blog Posts**:
- Uber's notification platform: "Building Uber's Notification System"
- Facebook's notification system: "Under the Hood: Building Notifications"
- Instagram's push notification: "Optimizing iOS Push Notification Delivery at Scale"
- Twitter's Manhattan storage: "Manhattan: Twitter's Distributed Database"

### System Design

**Resources**:
- "System Design Primer" (GitHub repository)
- Grokking the System Design Interview (Educative.io)
- ByteByteGo newsletter by Alex Xu
- High Scalability blog (highscalability.com)

### Mobile Push Notifications

**Documentation**:
- Apple APNs: developer.apple.com/notifications
- Google FCM: firebase.google.com/docs/cloud-messaging
- Web Push Protocol: RFC 8030

### Observability

**Books**:
- *Observability Engineering* by Charity Majors et al.
- *Distributed Tracing in Practice* by Austin Parker et al.

**Tools**:
- OpenTelemetry documentation
- Prometheus documentation
- Jaeger documentation

### Ethics & Design

**Books**:
- *Weapons of Math Destruction* by Cathy O'Neil
- *The Age of Surveillance Capitalism* by Shoshana Zuboff
- *Hooked: How to Build Habit-Forming Products* by Nir Eyal (with ethical caution)
- *Indistractable* by Nir Eyal (counterpoint to Hooked)

### Specific Topics

**Rate Limiting**:
- "Rate Limiting Strategies and Techniques" by Kong
- "Better Rate Limiting With Redis Sorted Sets" by Brandur Leach

**Circuit Breakers**:
- "Release It!" by Michael Nygard (patterns for resilience)
- Netflix Hystrix documentation

**Data Modeling**:
- "The Data Model Resource Book" by Len Silverston
- Cassandra data modeling best practices

**Capacity Planning**:
- "Every Programmer Should Know" (GitHub - latency numbers)
- "Back-of-the-envelope Calculations" (ByteByteGo)

---

## E. Quick Reference: Key Formulas

### Throughput

```
Throughput = Batch_Size / Batch_Time

Example: 1000 items / 100ms = 10,000 items/second
```

### Latency Percentiles

```
p99 latency = value at 99th percentile

If 1000 requests:
- Sort by latency
- p99 = latency of request #990
```

### Storage Sizing

```
Storage = Records × Record_Size × Replication_Factor

Example: 1B records × 1KB × 3 = 3 TB
```

### Bandwidth

```
Bandwidth = Throughput × Message_Size

Example: 1M msg/sec × 2 KB = 2 GB/sec = 16 Gbps
```

### Memory for Cache

```
Memory = Cached_Items × Item_Size

Example: 10M users × 25 bytes = 250 MB
```

### Request Rate per Server

```
Servers = Total_Requests / Requests_Per_Server

Example: 1M req/sec / 10K req/sec/server = 100 servers
```

### Exponential Backoff

```
Delay = Base_Delay × 2^attempt

Example: 100ms × 2^3 = 800ms (after 3rd retry)
```

### Availability

```
Availability = Uptime / (Uptime + Downtime)

99.9% = 43.8 minutes downtime/month
99.99% = 4.38 minutes downtime/month
```

---

## F. Common Pitfalls

### 1. **Underestimating scale**
- "1M users is a lot" → It's not at global scale
- Always design for 10-100× growth

### 2. **Synchronous operations in critical path**
- Database writes in API handlers → Use async queues
- External API calls without timeouts → Always set timeouts

### 3. **Ignoring failure modes**
- "Network is reliable" → It's not
- "Clocks are synchronized" → They're not

### 4. **Over-engineering early**
- "We might need this someday" → YAGNI (You Aren't Gonna Need It)
- Start simple, scale when needed

### 5. **Under-engineering observability**
- "We'll add metrics later" → You won't debug production without them
- Build observability from day 1

### 6. **Ignoring edge cases**
- "That'll never happen" → At scale, it will happen daily
- Every 0.01% case happens 10M times/day at 1B users

### 7. **Optimizing the wrong thing**
- Shaving 1ms off in-memory operations when the database call takes 100ms
- Profile before optimizing

### 8. **Not testing at scale**
- "Works on my machine with 1000 users" → What about 1M?
- Load test before going to production

### 9. **Tight coupling**
- Services that know about each other's internals
- Use event-driven architecture for loose coupling

### 10. **Poor data modeling**
- Schema that works for 1M rows but not 1B
- Model for scale from the start (or plan migration)

---

## G. System Design Checklist

Use this checklist when designing any large-scale system:

### Requirements

- [ ] Functional requirements clearly defined
- [ ] Non-functional requirements (latency, throughput, availability) specified
- [ ] Scale estimated (users, data volume, request rate)
- [ ] Growth projections calculated

### Architecture

- [ ] Components identified and isolated
- [ ] Data flow mapped
- [ ] API contracts defined
- [ ] Storage strategy chosen (SQL vs. NoSQL vs. hybrid)

### Scalability

- [ ] Horizontal scaling strategy defined
- [ ] Sharding/partitioning strategy chosen
- [ ] Load balancing approach selected
- [ ] Caching strategy designed

### Reliability

- [ ] Replication strategy defined
- [ ] Failure modes identified
- [ ] Circuit breakers implemented
- [ ] Retry logic with exponential backoff
- [ ] Dead letter queues for failed messages

### Performance

- [ ] Latency targets defined
- [ ] Throughput targets defined
- [ ] Indexing strategy for databases
- [ ] Caching for hot data
- [ ] Batching for efficiency

### Observability

- [ ] Metrics collection designed (RED/USE method)
- [ ] Distributed tracing implemented
- [ ] Structured logging configured
- [ ] Alerting rules defined
- [ ] Dashboards created

### Operations

- [ ] Deployment strategy defined
- [ ] Rollback procedure documented
- [ ] Database migration strategy
- [ ] Backup and restore procedures
- [ ] Capacity planning done

### Security

- [ ] Authentication implemented
- [ ] Authorization designed
- [ ] Data encryption (at rest and in transit)
- [ ] Rate limiting for abuse prevention
- [ ] Input validation

### Compliance

- [ ] GDPR requirements (if applicable)
- [ ] Data retention policies
- [ ] Right to erasure implemented
- [ ] Audit logging

---

## H. Recommended Tools & Technologies

### Message Queues
- **Kafka**: High-throughput, persistent, partitioned
- **RabbitMQ**: Flexible routing, AMQP support
- **AWS SQS/SNS**: Managed, simple, integrates with AWS
- **Google Pub/Sub**: Managed, global, at-least-once delivery

### Databases
- **Cassandra**: Wide-column, highly scalable, tunable consistency
- **DynamoDB**: Managed NoSQL, predictable performance
- **PostgreSQL**: RDBMS, JSONB support, excellent for hybrid workloads
- **Redis**: In-memory, caching, simple data structures

### Observability
- **Prometheus**: Metrics collection and storage
- **Grafana**: Metrics visualization
- **Jaeger**: Distributed tracing
- **OpenTelemetry**: Instrumentation standard
- **Elasticsearch**: Log aggregation and search

### API Gateway
- **Kong**: Open-source, plugin architecture
- **AWS API Gateway**: Managed, serverless-friendly
- **Envoy**: Modern proxy, service mesh ready

### Deployment
- **Kubernetes**: Container orchestration
- **Docker**: Containerization
- **Terraform**: Infrastructure as code
- **ArgoCD**: GitOps for Kubernetes

---

## I. Parting Wisdom

**On Scale**: "Scale is not just bigger—it's different. Solutions that work at 1K users won't work at 1M, and solutions at 1M won't work at 1B."

**On Simplicity**: "The best system design is the simplest one that meets requirements. Every component is a liability."

**On Failure**: "Design for failure. The question is not 'if' but 'when' things will break."

**On Metrics**: "You can't improve what you don't measure. But don't measure everything—measure what matters."

**On Trade-offs**: "There are no perfect solutions, only intelligent trade-offs. Know what you're optimizing for."

**On Evolution**: "The only constant is change. Design for the system you have, but architect for the system you'll need."

**On Ethics**: "Technical capability doesn't imply permission. With great scale comes great responsibility."

---

**Thank you for reading "The Architecture of Attention."**

**Now go build something amazing—and use it wisely.**
