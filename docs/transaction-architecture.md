# Transaction Service architecture

## Container diagram

```mermaid
flowchart LR
    Client --> Individuals[Individuals API]
    Individuals --> Transaction[Transaction Service]
    Transaction --> Sharding[ShardingSphere JDBC]
    Sharding --> Shard0[(PostgreSQL shard 0)]
    Sharding --> Shard1[(PostgreSQL shard 1)]
    Transaction --> Kafka[(Kafka)]
    Kafka --> Provider[Payment provider]
    Provider --> Kafka
    Transaction --> Prometheus
    Transaction --> Tempo
    Prometheus --> Grafana
    Tempo --> Grafana
```

## Component diagram

```mermaid
flowchart TB
    API[Generated OpenAPI controllers] --> WalletService
    API --> TransactionService
    WalletService --> WalletRepository
    TransactionService --> WalletRepository
    TransactionService --> TransactionRepository
    TransactionService --> OutboxService
    OutboxService --> OutboxRepository
    OutboxRelay --> OutboxRepository
    OutboxRelay --> Kafka[(Kafka)]
    Kafka --> KafkaConsumer
    KafkaConsumer --> TransactionService
    KafkaConsumer -. после исчерпания попыток .-> DLT[(*.DLT)]
    DltReplay[actuator/dltreplay] --> DLT
    WalletRepository --> ShardingSphere
    TransactionRepository --> ShardingSphere
    OutboxRepository --> ShardingSphere
```

Individuals API проверяет, что `userUid` запроса совпадает с `sub` Bearer-токена, и вызывает
Transaction Service сгенерированным Feign-клиентом.

## Deposit

```mermaid
sequenceDiagram
    participant C as Client
    participant I as Individuals API
    participant T as Transaction Service
    participant DB as PostgreSQL
    participant K as Kafka
    C->>I: POST /transactions/deposit/init
    I->>T: init
    T-->>I: fee, totalAmount, available
    Note over T,DB: No database write during init
    C->>I: POST /transactions/deposit/confirm
    I->>T: confirm
    T->>DB: transaction PENDING + outbox DepositRequested (одна транзакция)
    T-->>I: PENDING + transactionId
    loop OutboxRelay, пока брокер не подтвердит запись
        T->>K: DepositRequested
    end
    K->>T: DepositCompleted
    T->>DB: credit wallet + COMPLETED
```

## Transfer

```mermaid
sequenceDiagram
    participant C as Client
    participant I as Individuals API
    participant T as Transaction Service
    participant DB as PostgreSQL
    C->>I: POST /transactions/transfer/init
    I->>T: init
    T-->>I: fee, totalAmount, available
    C->>I: POST /transactions/transfer/confirm
    I->>T: confirm
    T->>DB: lock wallets in UUID order
    Note over T,DB: кошельки могут быть на разных шардах — XA-транзакция (2PC)
    T->>DB: debit + credit + transaction COMPLETED
    T-->>I: COMPLETED + transactionId
```

## Withdrawal

```mermaid
sequenceDiagram
    participant C as Client
    participant I as Individuals API
    participant T as Transaction Service
    participant DB as PostgreSQL
    participant K as Kafka
    C->>I: POST /transactions/withdrawal/init
    I->>T: init
    T-->>I: fee, totalAmount, available
    C->>I: POST /transactions/withdrawal/confirm
    I->>T: confirm
    T->>DB: debit wallet + transaction PENDING + outbox WithdrawalRequested (одна транзакция)
    T-->>I: PENDING + transactionId
    loop OutboxRelay, пока брокер не подтвердит запись
        T->>K: WithdrawalRequested
    end
    K->>T: WithdrawalCompleted / WithdrawalFailed
    alt completed
        T->>DB: transaction COMPLETED
    else failed
        T->>DB: refund amount and fee + transaction FAILED
    end
```

## Обработка ответов провайдера

- Временная ошибка БД — повторы без ограничения числа попыток с паузой до `transaction.kafka.retry.max-interval`;
  следующие сообщения партиции ждут, offset не сдвигается.
- Невалидное сообщение — сразу в `<topic>.DLT`.
- Прочие ошибки — `transaction.kafka.retry.attempts` повторов, затем в `<topic>.DLT`.
- `POST /api/v1/actuator/dltreplay` переотправляет накопившиеся в DLT сообщения в исходные топики;
  финализация идемпотентна, поэтому повторная доставка безопасна.
