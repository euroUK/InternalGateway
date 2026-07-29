# Internal Gateway

Архитектура внутреннего gateway для фронтальных продуктовых систем банка: декларативные модули, platform capabilities, Kafka messaging, PoC «Депозитные предложения».

## Структура

```
InternalGateway/
├── README.md
├── docker-compose.yml
├── pom.xml
├── gateway/                         # Internal Gateway (Java, MVC)
├── scg-gateway/                     # Spring Cloud Gateway WebFlux (A/B benchmark)
├── dotnet-gateway/                  # ASP.NET Core 10 gateway (A/B benchmark)
├── services/
│   ├── deposit-offer-service/       # Бэкенд депозитов (Java)
│   └── deposit-processor/           # Mock процессора депозитов (Java)
├── frontend/                        # Demo UI (vanilla JS + nginx)
├── docs/
│   └── internal-gateway-architecture.md
├── dsl/
│   ├── deposit-opening-gateway.dsl.yaml
│   └── deposit-messaging-gateway.dsl.yaml
└── plans/
    ├── system-platform-boundary_d2ec3495.plan.md
    └── deposit-offers-gateway-poc_459d38eb.plan.md
```

## Локальный запуск PoC

Требования: Docker Desktop (или Docker Engine + Compose v2).

```bash
docker compose build
docker compose up -d
```

| Сервис | URL | Описание |
|--------|-----|----------|
| Frontend demo | http://localhost:3000 | UI поиска предложений и processor sync |
| Gateway observability | http://localhost:3000/gateway.html | Конфигурация DSL, маршруты, журнал запросов |
| Event mapping | http://localhost:3000/mapping.html | Маппинг processor Kafka → internal canonical model |
| Internal Gateway API | http://localhost:8080 | Ingress + capabilities + Kafka fan-out |
| Deposit Processor mock | http://localhost:8091 | Demo API публикации событий в Kafka |
| Kafka | localhost:9092 | Брокер (debug) |

Остановка:

```bash
docker compose down
```

Сборка без Docker (нужен JDK 21 и Maven):

```bash
./mvnw package -DskipTests
```

## E2E сценарий проверки

1. `docker compose up -d` — все 5 контейнеров переходят в healthy.
2. Deposit Processor при старте публикует `DepositOfferCreated` в Kafka.
3. Gateway consumer получает события и fan-out в `deposit-offer-service`.
4. Открыть http://localhost:3000, выполнить поиск (org-demo-001, acc-demo-001, сумма 500000, срок 12).
5. В результатах видны offers из processor catalog (`proc-offer-001`, `proc-offer-002`, …) и local catalog.
6. В секции «Processor sync demo» нажать **Publish Created** для нового offer, затем повторить search — offer появляется.
7. Нажать **Publish Closed** — offer исчезает из результатов search.
8. `docker compose restart deposit-offer-service` — каталог сохраняется в volume `offer-service-data`.

## Benchmark RPS / latency overhead

Сравнение throughput и оверхеда gateway относительно прямого вызова backend
(кастомный gateway vs Spring Cloud Gateway WebFlux):

```bash
docker compose up -d
docker compose --profile benchmark run --rm -e PROFILE=smoke k6

# Isolated SCG contour (port 8081 + dedicated backend)
docker compose --profile scg-benchmark up -d deposit-offer-service-scg scg-gateway
docker compose --profile scg-benchmark run --rm -e PROFILE=smoke k6-scg

# Isolated .NET 10 contour (port 8082 + dedicated backend + Kafka fan-out)
docker compose --profile dotnet-benchmark up -d kafka deposit-offer-service-dotnet dotnet-gateway
docker compose --profile dotnet-benchmark run --rm -e PROFILE=smoke k6-dotnet
```

Подробности, переменные окружения и формат отчёта:
[scripts/benchmark/README.md](scripts/benchmark/README.md).
Модуль SCG: [scg-gateway](scg-gateway).
Модуль .NET: [dotnet-gateway](dotnet-gateway).

## С чего начать (архитектура)

1. [docs/internal-gateway-architecture.md](docs/internal-gateway-architecture.md) — полное описание, плюсы/минусы, план PoC, чеклисты.
2. [dsl/deposit-opening-gateway.dsl.yaml](dsl/deposit-opening-gateway.dsl.yaml) — пример DSL для HTTP и platform capabilities.
3. [dsl/deposit-messaging-gateway.dsl.yaml](dsl/deposit-messaging-gateway.dsl.yaml) — Kafka: publish, fan-out, подписка на процессор депозитов.

## Кратко

- **Internal Gateway** — внутренняя граница продуктовой системы (после банковского API Gateway).
- Продуктовые МС не содержат platform starters / lite-service clients.
- Gateway: access, Business Control, audit, retry, rate limit, cache, provider selection (lite vs bank API).
- **Messaging plane:** publish/consume Kafka через Gateway; подписка на события депозитного процессора (Created/Updated/Closed).
- **PoC:** Deposit Offer Service — подбор депозитных предложений.

## Статус

Draft для согласования. Версия архитектурного документа: 1.1. Runnable PoC доступен через `docker compose`.
