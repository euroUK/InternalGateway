# Internal Gateway

Архитектура внутреннего gateway для фронтальных продуктовых систем банка: декларативные модули, platform capabilities, Kafka messaging, PoC «Депозитные предложения».

## Структура

```
InternalGateway/
├── README.md
├── docs/
│   └── internal-gateway-architecture.md   # Основной документ для архитекторов и аналитиков
├── dsl/
│   ├── deposit-opening-gateway.dsl.yaml # HTTP ingress, capabilities, Business Control
│   └── deposit-messaging-gateway.dsl.yaml # Kafka publish/consume, processor subscription
└── plans/
    ├── system-platform-boundary_d2ec3495.plan.md
    └── deposit-offers-gateway-poc_459d38eb.plan.md
```

## С чего начать

1. [docs/internal-gateway-architecture.md](docs/internal-gateway-architecture.md) — полное описание, плюсы/минусы, план PoC, чеклисты.
2. [dsl/deposit-opening-gateway.dsl.yaml](dsl/deposit-opening-gateway.dsl.yaml) — пример DSL для HTTP и platform capabilities.
3. [dsl/deposit-messaging-gateway.dsl.yaml](dsl/deposit-messaging-gateway.dsl.yaml) — Kafka: publish, fan-out, подписка на процессор депозитов.

## Кратко

- **Internal Gateway** — внутренняя граница продуктовой системы (после банковского API Gateway).
- Продуктовые МС не содержат platform starters / lite-service clients.
- Gateway: access, Business Control, audit, retry, rate limit, cache, provider selection (lite vs bank API).
- **Messaging plane:** publish/consume Kafka через Gateway; подписка на события депозитного процессора (Created/Updated/Closed).
- **PoC:** новый Deposit Offer Service — подбор депозитных предложений.

## Статус

Draft для согласования. Версия архитектурного документа: 1.1.
