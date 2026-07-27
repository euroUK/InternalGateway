# Internal Gateway

Архитектура внутреннего gateway для фронтальных продуктовых систем банка: декларативные модули, platform capabilities, Kafka messaging, PoC «Депозитные предложения».

## Структура

```
InternalGateway/
├── README.md
├── docker-compose.yml
├── pom.xml
├── gateway/                         # Internal Gateway (Java)
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
