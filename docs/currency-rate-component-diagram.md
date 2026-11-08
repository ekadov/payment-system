# Currency Rate Service - component diagram

Компоненты разделяют входные REST-запросы, выбор провайдера, проверку котировок, расчёт
курсов и ведение истории.

```mermaid
flowchart TB
    Individuals[Individuals API]
    ProviderA[Rate Provider A]
    ProviderB[Rate Provider B]
    Db[(Currency PostgreSQL)]

    subgraph CurrencyRateService[Currency Rate Service]
        Contract[OpenAPI contract<br/>generated API and DTO]
        Controller[CurrencyRateController<br/>REST adapter]
        Query[RateQueryService<br/>current and historical rates]

        Scheduler[RateUpdateScheduler<br/>Spring Scheduler + ShedLock]
        Update[RateUpdateService<br/>update orchestration]
        Selector[ProviderSelectionService<br/>active providers by priority]
        ProviderRegistry[ProviderClientRegistry<br/>provider code to HTTP client]
        ClientA[Provider A HTTP client]
        ClientB[Provider B HTTP client]
        Validator[ProviderResponseValidator<br/>coverage, timestamp, positive quotes]
        Calculator[CrossRateCalculator<br/>all directed pairs A to B]
        Adjustment[RateAdjustmentResolver<br/>coefficient at switchAt]
        Writer[RateHistoryWriter<br/>atomic interval switch]

        CurrencyRepo[CurrencyRepository]
        ProviderRepo[RateProviderRepository]
        AdjustmentRepo[RateAdjustmentRepository]
        RateRepo[ConversionRateRepository]
        Metrics[Micrometer metrics and JSON logs]
    end

    Individuals -->|generated client| Contract
    Contract --> Controller
    Controller --> Query
    Query --> RateRepo

    Scheduler -->|updateRates| Update
    Update --> CurrencyRepo
    Update --> Selector
    Selector --> ProviderRepo
    Selector --> ProviderRegistry
    ProviderRegistry --> ClientA
    ProviderRegistry --> ClientB
    ClientA --> ProviderA
    ClientB --> ProviderB
    Update --> Validator
    Update --> Calculator
    Calculator --> Adjustment
    Adjustment --> AdjustmentRepo
    Update --> Writer
    Writer --> RateRepo

    CurrencyRepo --> Db
    ProviderRepo --> Db
    AdjustmentRepo --> Db
    RateRepo --> Db

    Query --> Metrics
    Update --> Metrics
```

## Ответственность компонентов

- `CurrencyRateController` преобразует REST-запрос в вызов use case и не содержит
  расчётной логики.
- `RateQueryService` возвращает текущий курс, либо курс, действовавший в указанный момент.
- `RateUpdateScheduler` только инициирует обновление. Межэкземплярную блокировку выполняет
  ShedLock через таблицу в Currency PostgreSQL.
- `ProviderSelectionService` получает активных провайдеров в порядке `priority ASC`:
  меньшее число означает более высокий приоритет.
- `ProviderClientRegistry` выбирает HTTP-клиент по `provider_code`. Это позволяет иметь
  разные форматы запросов и ответов у поставщиков.
- `ProviderResponseValidator` не допускает расчёт по частичному, устаревшему или
  некорректному снимку котировок.
- `CrossRateCalculator` строит `N × (N − 1)` направленных пар в памяти.
- `RateAdjustmentResolver` выбирает коэффициент для направленной пары в момент
  `switchAt`. При отсутствии настройки используется коэффициент `1`.
- `RateHistoryWriter` одной транзакцией закрывает прежние интервалы и вставляет полный
  новый набор курсов. До этого шага таблица курсов не изменяется.

Формирование и проверка защищённого `quoteToken` относится к Transaction Service: 
он применяет курс при переводе и фиксирует финансовую операцию. Currency Rate Service
возвращает идентификатор неизменяемой записи курса и её параметры, но не токен
для клиента.
