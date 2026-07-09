# person-service

Микросервис управления пользователями: хранит и изменяет агрегат **user + individual + address**.
Источник правды по пользовательским данным.

## Стек

- Java 25, Spring Boot 4
- PostgreSQL + Flyway (схема 'person')
- Spring Data JPA + Hibernate Envers (аудит с флагами изменённых полей)
- OpenAPI (contract-first) + OpenAPI Generator: серверные интерфейсы ('delegatePattern', 'useSpringBoot4') и клиент
- RFC 9457 Problem Details ('application/problem+json')
- Actuator + Micrometer/Prometheus, трассировка через OpenTelemetry (OTLP -> Tempo)
- JSON-журналирование (logback + logstash encoder) с 'traceId'/'spanId'/'service.name'

## HTTP API

| Операция              | Метод и путь                          | Успех |
|-----------------------|---------------------------------------|-------|
| Создание              | 'POST /api/v1/users'                  | 201   |
| Чтение по id          | 'GET /api/v1/users/{id}'              | 200   |
| Чтение по email       | 'GET /api/v1/users/by-email?email='   | 200   |
| Частичное изменение   | 'PATCH /api/v1/users/{id}'            | 200   |
| Удаление              | 'DELETE /api/v1/users/{id}'           | 204   |

Контракт: [openapi/person-service.yaml](openapi/person-service.yaml). Swagger UI: '/swagger-ui.html'.

## Сборка и тесты

'''bash
./gradlew build            # сборка + unit-тесты + проверка покрытия (>= 80%)
./gradlew test             # интеграционные тесты поднимают PostgreSQL через Testcontainers
'''

Сгенерированный код лежит в 'build/generated' (в 'src/main/java' не попадает).

## Клиентский артефакт (person-service-client)

Отдельный подпроект 'client' генерирует HTTP Service Client и публикует артефакт 
'ru.personservice:person-service-client'.

'''bash
# публикация в локальный стенд-репозиторий (infrastructure/local-repo)
./gradlew :person-service-client:publishMavenJavaPublicationToLocalRepoRepository

# публикация в Nexus (snapshots/releases в разные hosted-репозитории; потребители ходят через group-адрес)
./gradlew :person-service-client:publishMavenJavaPublicationToNexusRepository \
  -PnexusUsername=admin -PnexusPassword=*** \
  -PnexusSnapshotsUrl=http://localhost:8083/repository/maven-snapshots/ \
  -PnexusReleasesUrl=http://localhost:8083/repository/maven-releases/
'''

Потребитель (например 'individuals-api') подключает 'ru.personservice:person-service-client:0.0.1-SNAPSHOT'
и оборачивает интерфейс 'UsersApi' в 'HttpServiceProxyFactory'.

## Локальный стенд

'''bash
docker compose up --build
'''

Поднимаются: 'person-service' (8082), 'person-postgres', 'prometheus' (9090), 'grafana' (3000),
'tempo' (3200 / OTLP 4318), 'nexus' (8083), а также сервисы модуля 1 ('individuals-api', 'keycloak').
