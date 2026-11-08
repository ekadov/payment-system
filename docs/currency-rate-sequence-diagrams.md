# Currency Rate Service - sequence diagrams

## Плановое обновление курсов

```mermaid
sequenceDiagram
    autonumber
    participant S as Spring Scheduler
    participant L as ShedLock
    participant U as RateUpdateService
    participant DB as Currency PostgreSQL
    participant P as Provider clients
    participant V as ProviderResponseValidator
    participant C as Calculator and Adjustments
    participant M as Metrics and Logs

    S->>L: Попытка получить lock updateRates
    L->>DB: Захватить lock, если lock_until <= now
    alt Блокировка занята другим экземпляром
        L-->>S: Пропустить запуск
        S->>M: update skipped
    else Блокировка получена
        L-->>S: Выполнить scheduled method
        S->>U: updateRates()
        U->>DB: Активные валюты, провайдеры, интервалы коэффициентов и текущие курсы
        DB-->>U: Конфигурация, providers ordered by priority ASC

        alt Нет активных провайдеров
            U->>M: update failed, reason=no_active_provider
            Note over U,DB: Текущие интервалы не изменяются
        else Есть активные провайдеры
            loop Пока не получен первый полный валидный снимок
                U->>P: Запросить котировки у следующего провайдера
                P-->>U: Снимок или ошибка
                U->>V: validate(snapshot, activeCurrencies, currentRates)
                alt Ошибка транспорта или невалидный снимок
                    V-->>U: invalid
                    U->>M: provider attempt failed
                    Note over U,P: Перейти к следующему provider по priority ASC
                else Полный валидный снимок
                    V-->>U: valid snapshot and switchAt
                    U->>U: Выбрать провайдера и прекратить попытки
                end
            end

            alt Валидный снимок не найден
                U->>M: update failed, reason=no_valid_snapshot
                Note over U,DB: Текущие интервалы не изменяются
            else Провайдер выбран
                Note over U,V: Проверены общий UTC timestamp, полнота набора,<br/>quote != null и quote > 0,<br/>switchAt новее текущего набора
                U->>C: Построить все направленные пары A to B, A != B
                C->>C: crossRate = quote(B) / quote(A)
                C->>C: Выбрать coefficient(A to B, switchAt), default=1
                C->>C: adjustedRate = crossRate * coefficient
                C-->>U: Полный набор N * (N - 1)
                U->>V: Проверить количество, положительность и уникальность пар

                alt Расчёт или коэффициенты невалидны
                    V-->>U: invalid calculated set
                    U->>M: update failed, reason=calculation_error
                    Note over U,DB: Текущие интервалы не изменяются
                else Набор полностью валиден
                    V-->>U: valid calculated set
                    U->>DB: BEGIN
                    U->>DB: Закрыть текущие интервалы: rate_end_time = switchAt
                    U->>DB: Вставить полный набор: begin = switchAt, end = NULL
                    U->>DB: COMMIT
                    U->>M: success, duration, provider, pair count
                end
            end
        end

        U-->>S: Обновление завершено
        S->>L: Освободить блокировку
        L->>DB: Обновить lock_until
    end
```

HTTP 200 от провайдера по идее не означает успешное обновление. До изменения истории
должны успешно завершиться проверка входного снимка, расчёт всех пар и применение всех
коэффициентов. Любая ошибка оставляет прежний набор актуальным.

## Межвалютный перевод: init и confirm

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant I as Individuals API
    participant T as Transaction Service
    participant R as Currency Rate Service
    participant RDB as Currency PostgreSQL
    participant TDB as Transaction PostgreSQL

    C->>I: POST /v1/transactions/transfer/init<br/>wallets + amount
    I->>T: Получить исходный и целевой кошельки
    T-->>I: Wallet IDs, owners and currency codes

    alt Валюты различаются
        I->>R: GET /api/v1/rates?from=USD&to=EUR
        R->>RDB: Найти rate_end_time IS NULL
        alt Актуальный курс отсутствует
            RDB-->>R: Empty
            R-->>I: 404 ErrorResponse
            I-->>C: Курс недоступен
        else Актуальный курс найден
            RDB-->>R: rateId, rate, effectiveAt, providerCode
            R-->>I: RateResponse
            I->>T: init with authenticated user,<br/>wallets, amount and immutable rate snapshot
        end
    else Валюты совпадают
        I->>T: init with authenticated user,<br/>wallets, amount and rate=1
    end

    opt Курс доступен либо валюты совпадают
        T->>T: Проверить пользователя, кошельки, валюты и сумму
        T->>T: Рассчитать fee, debitAmount и creditAmount
        T->>T: Создать quoteId и expiresAt
        T->>T: Подписать quoteToken серверным ключом
        Note over T: Token связывает quoteId, user, оба wallet ID,<br/>amount, currencies, rateId/rate, рассчитанные суммы,<br/>issuedAt и expiresAt
        T-->>I: Условия транзакции + opaque quoteToken
        I-->>C: Условия транзакции + opaque quoteToken

        C->>I: POST /v1/transactions/transfer/confirm<br/>Idempotency-Key + quoteToken
        I->>T: confirm with JWT subject,<br/>Idempotency-Key + quoteToken
        T->>T: Проверить подпись и expiresAt
        T->>T: Сопоставить JWT subject и все условия token

        alt Token неверен, истёк или принадлежит другому пользователю
            T-->>I: 400/403 quote rejected
            I-->>C: Подтверждение отклонено
        else Token валиден
            T->>TDB: Проверить Idempotency-Key и UNIQUE quoteId
            alt Операция уже подтверждена
                TDB-->>T: Существующий результат
                T-->>I: Тот же transactionId и status
                I-->>C: Тот же результат
            else Первое подтверждение
                T->>TDB: Заблокировать кошельки в UUID order
                T->>TDB: Проверить владельцев, валюты и достаточность баланса
                T->>TDB: Debit + credit + transaction + quoteId
                Note over T,R: Курс повторно не запрашивается
                TDB-->>T: COMMIT
                T-->>I: COMPLETED + transactionId
                I-->>C: COMPLETED + transactionId
            end
        end
    end
```

`quoteToken` - непрозрачное для клиента подписанное значение, например JWS.
Transaction Service создаёт и проверяет его одним серверным ключом. Клиент не передаёт на
`confirm` курс или рассчитанную сумму отдельными доверенными полями.

`quoteId` сохраняется при первом успешном `confirm` и имеет уникальное ограничение. Поэтому
один quote нельзя применить повторно с другим `Idempotency-Key`; повтор исходного запроса
возвращает уже созданную транзакцию.

## Контрактные изменения для перевода

Целевая версия контракта должна:

- дополнить `RateResponse` полями `rateId` и точным временем начала действия;
- позволить Individuals API передать в `init` неизменяемый снимок курса во внутренний
  контракт Transaction Service;
- вернуть из `init` `quoteToken` и `expiresAt`;
- принимать на `confirm` `quoteToken`, не доверяя присланным клиентом финансовым расчётам;
- возвращать отдельные ошибки для неверного и просроченного quote.