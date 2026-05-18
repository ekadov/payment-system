# Payment System (Individuals API)

Репозиторий микросервиса: 'individuals-api'.
Микросервис представляет из себя оркестратор авторизации через Keycloak и предоставляет API для:
- регистрации
- логина
- token refresh
- получение инфо о текущем пользователе.

## Stack

- Java 21+
- Spring Boot 3.5.x (WebFlux, Security, Actuator)
- Keycloak + PostgreSQL
- Prometheus
- Loki
- Grafana
- Docker Compose

## Структура

```text
payment-system/
  docker-compose.yaml
  individuals-api/
    Dockerfile
    openapi/individuals-api.yaml
    src/main/resources/
      application.yaml
      application-docker.yaml
      logback-spring.xml
      realm-config.json
  prometheus/prometheus.yml
  loki/loki-config.yaml
  grafana/
    provisioning/
      datasources/loki.yaml
      dashboards/dashboards.yaml
    dashboards/individuals-observability.json
```

## API

- Эндпоинты аутентификации:
  - `POST /v1/auth/registration`
  - `POST /v1/auth/login`
  - `POST /v1/auth/refresh-token`
  - `GET /v1/auth/me`
- Генерация DTO через OpenAPI (`openapi-generator`)
- Prometheus метрики через Actuator (`/actuator/prometheus`)
- Сбор логов и отправка в Loki
- Grafana datasource provisioning (Prometheus + Loki)
- Grafana dashboard provisioning (`Individuals API Observability`)

## Требования к развёртыванию

- Наличие Docker
- Свободные порты: `3000`, `3100`, `5433`, `8080`, `8081`, `9090`

## Запуск

Из корня репозитория:

```bash
docker compose up -d --build
```

Проверка статуса:

```bash
docker compose ps
```

Ожидается, что все сервисы будут в состоянии `Up (healthy)`:

- `individuals-keycloak`
- `keycloak-postgres`
- `individuals-api`
- `prometheus`
- `loki`
- `grafana`

## URL для доступа к компонентам

- Individuals API: `http://localhost:8081`
- Keycloak: `http://localhost:8080`
- Prometheus: `http://localhost:9090`
- Loki readiness: `http://localhost:3100/ready`
- Grafana: `http://localhost:3000` (`admin` / `admin`)

## Curl API (заполните своими данными)

### 1. Registration

```bash
curl -X POST http://localhost:8081/v1/auth/registration \
  -H "Content-Type: application/json" \
  -d '{"email":"user1@example.com","password":"Password123!","confirm_password":"Password123!"}'
```

### 2. Login

```bash
curl -X POST http://localhost:8081/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user1@example.com","password":"Password123!"}'
```

### 3. Refresh token

```bash
curl -X POST http://localhost:8081/v1/auth/refresh-token \
  -H "Content-Type: application/json" \
  -d '{"refresh_token":"<PASTE_REFRESH_TOKEN_HERE>"}'
```

### 4. Current user

```bash
curl http://localhost:8081/v1/auth/me \
  -H "Authorization: Bearer <PASTE_ACCESS_TOKEN_HERE>"
```

## Просмотр метрик

### Prometheus

Открыть `http://localhost:9090/targets`.

Target `individuals-api` должно быть `UP`.

Примерные запросы:

- `up{job="individuals-api"}`
- `rate(http_server_requests_seconds_count[1m])`
- `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))`

### Loki

-> Grafana Explore -> datasource `Loki`:

- `{app="individuals-api"}`
- `{app="individuals-api", level=~"ERROR|WARN"}`

### Grafana Dashboard

Открыть Grafana (`http://localhost:3000`) и найти папку `Individuals API`.

Dashboard: `Individuals API Observability`

Уже включены следующие метрики:

- Requests Per Second
- HTTP Latency p95
- Error Rate (4xx/5xx)
- Latency percentiles (p50/p95/p99)
- WARN/ERROR logs panel

## Полезные команды

Перезагрузить проект:

```bash
docker compose down
docker compose up -d --build
```

Посмотреть логи контейнеров:

```bash
docker compose logs -f individuals-api
docker compose logs -f keycloak
docker compose logs -f grafana
```

Перезагрузить только Grafana (чтобы перезапустить provisioning):

```bash
docker compose restart grafana
```

## Чтобы отключить проект (все контейнеры)

```bash
docker compose down
```

## Запуск тестов

Из individuals-api запустить:

```bash
./gradlew test
```