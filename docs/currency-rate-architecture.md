# Currency Rate Service architecture

Диаграммы микросервиса для получения, хранения и предоставления курсов валют по запросу других сервисов.

## Container diagram

```mermaid
flowchart LR
    Client([Внешний клиент])
    Provider[Внешний провайдер<br/>курсов валют]

    subgraph Payment[Payment System]
        Individuals[Individuals API<br/>Spring Boot, :8081]
        Transactions[Transaction Service<br/>Spring Boot, :8084]
        Rates[Currency Rate Service<br/>Spring Boot, :8083]
        RateDb[(Currency PostgreSQL<br/>история курсов и ShedLock)]

        Prometheus[Prometheus]
        Loki[Loki]
        Grafana[Grafana]
    end

    Client -->|REST, единственная<br/>внешняя точка входа| Individuals
    Individuals -->|OpenAPI-generated client<br/>GET /api/v1/rates| Rates
    Individuals -->|OpenAPI-generated client<br/>операции и кошельки| Transactions
    Rates -->|REST/HTTPS| Provider
    Rates -->|Spring Data JPA| RateDb

    Prometheus -->|scrape /actuator/prometheus| Rates
    Rates -->|JSON logs| Loki
    Prometheus -->|datasource| Grafana
    Loki -->|datasource| Grafana
```

Currency Rate Service владеет отдельной базой данных. Базы/шарды Transaction Service на
диаграмме не показаны, потому что новый сервис не должен обращаться к ним напрямую.

## Component diagram

```mermaid
flowchart TB
    subgraph CRS[Currency Rate Service]
        Contract[OpenAPI contract<br/>generated API and DTO]
        Controller[CurrencyRateController<br/>REST adapter]
        Scheduler[RateUpdateScheduler<br/>Spring Scheduler + ShedLock]
        Query[RateQueryService<br/>поиск актуального/исторического курса]
        Update[RateUpdateService<br/>оркестрация обновления]
        Calculator[CrossRateCalculator<br/>все направленные пары A -> B]
        Adjustment[RateAdjustmentService<br/>корректирующие коэффициенты]
        ProviderClient[ExternalRateProviderClient<br/>HTTP adapter]
        Repositories[JPA repositories]
        Metrics[Micrometer metrics]
    end

    Individuals[Individuals API] -->|Feign/generated client| Contract
    Contract --> Controller
    Controller --> Query
    Scheduler --> Update
    Update --> ProviderClient
    Update --> Calculator
    Calculator --> Adjustment
    Query --> Repositories
    Update --> Repositories
    Adjustment --> Repositories
    Update --> Metrics
    Query --> Metrics

    ProviderClient --> Provider[External Rate Provider]
    Repositories --> Db[(Currency PostgreSQL)]
```

Контроллер не содержит расчётной логики. Планировщик отвечает только за запуск сценария,
а блокировка ShedLock хранится в той же PostgreSQL, что и данные сервиса.

## Data model

```mermaid
erDiagram
    CURRENCIES {
        bigint id PK
        timestamp created_at
        timestamp modified_at
        varchar code UK
        integer iso_code UK
        varchar description
        boolean active
        varchar symbol
    }

    RATE_PROVIDERS {
        varchar provider_code PK
        timestamp created_at
        timestamp modified_at
        varchar provider_name UK
        varchar description
        integer priority
        boolean active
    }

    CONVERSION_RATES {
        bigint id PK
        timestamp created_at
        timestamp modified_at
        varchar source_code FK
        varchar destination_code FK
        timestamp rate_begin_time
        timestamp rate_end_time
        numeric rate
        varchar provider_code FK
    }

    RATE_ADJUSTMENTS_PROPOSED {
        bigint id PK
        varchar source_code FK
        varchar destination_code FK
        numeric coefficient
        timestamp valid_from
        timestamp valid_to
        boolean active
    }

    SHEDLOCK {
        varchar name PK
        timestamp lock_until
        timestamp locked_at
        varchar locked_by
    }

    CURRENCIES ||--o{ CONVERSION_RATES : "source currency"
    CURRENCIES ||--o{ CONVERSION_RATES : "destination currency"
    RATE_PROVIDERS o|--o{ CONVERSION_RATES : provides
    CURRENCIES ||--o{ RATE_ADJUSTMENTS_PROPOSED : "source currency"
    CURRENCIES ||--o{ RATE_ADJUSTMENTS_PROPOSED : "destination currency"
```

`RATE_ADJUSTMENTS_PROPOSED` — необходимое расширение: т.к. требуется брать коэффициент
из таблицы, но такой таблицы в DDL нет. `SHEDLOCK` технически не связан
внешними ключами с бизнес-таблицами.

Для быстрого поиска курса потребуется составной индекс как минимум по
`(source_code, destination_code, rate_begin_time, rate_end_time)`.

## Scheduled rate update

```mermaid
sequenceDiagram
    autonumber
    participant S as Spring Scheduler
    participant L as ShedLock
    participant U as RateUpdateService
    participant P as External Provider
    participant DB as Currency PostgreSQL
    participant M as Metrics / Logs

    S->>L: Попытка получить lock updateRates
    L->>DB: INSERT/UPDATE shedlock if lock_until <= now
    alt Блокировка получена
        L-->>S: Выполнить scheduled method
        S->>U: updateRates()
        U->>P: Получить базовые котировки
        P-->>U: Котировки + время провайдера
        U->>DB: Активные валюты, провайдеры и коэффициенты
        DB-->>U: Справочники и настройки
        U->>U: Построить все пары A -> B, A != B
        U->>U: crossRate = quote(B) / quote(A)
        U->>U: adjustedRate = crossRate × coefficient
        U->>DB: В одной транзакции закрыть старые интервалы и вставить новые курсы
        U->>M: success, duration, pair count
        U-->>S: Обновление завершено
        S->>L: Освободить блокировку
        L->>DB: Обновить lock_until
    else Блокировка занята другим экземпляром
        L-->>S: Пропустить запуск
        S->>M: skipped update
    end
```

При ошибке провайдера существующие актуальные курсы остаются неизменными, ошибка
логируется, а метрика неуспешного обновления увеличивается.

## Cross-currency transfer initialization

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant I as Individuals API
    participant T as Transaction Service
    participant R as Currency Rate Service
    participant DB as Currency PostgreSQL

    C->>I: POST /v1/transactions/transfer/init
    I->>T: Получить исходный и целевой кошельки
    T-->>I: Wallets with currency codes
    alt Валюты совпадают
        I->>T: init transfer без конвертации
        T-->>I: Условия транзакции
        I-->>C: Условия транзакции
    else Валюты различаются
        I->>R: GET /api/v1/rates?from=USD&to=EUR&timestamp=...
        R->>DB: Найти курс, чей интервал содержит timestamp
        alt Курс найден
            DB-->>R: Conversion rate + provider
            R-->>I: RateResponse
            I->>T: init transfer с курсом и рассчитанной суммой
            T-->>I: Условия транзакции
            I-->>C: Условия транзакции с курсом
        else Курс отсутствует
            DB-->>R: Empty
            R-->>I: 404 ErrorResponse
            I-->>C: Ошибка: курс недоступен
        end
    end
```

Курс, его timestamp/идентификатор и рассчитанные суммы
должны фиксироваться в условиях транзакции и повторно использоваться на `confirm`, а не
запрашиваться заново.

## Некоторые детали

1. В текущем контракте Transaction Service у `TransactionRequest` нет полей для курса,
   валюты назначения и рассчитанной суммы. Надо поменять контракт.
2. В DDL отсутствует таблица корректирующих коэффициентов; на ER-диаграмме показан
   минимальный вариант.