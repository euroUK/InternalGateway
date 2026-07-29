# ASP.NET Core 10 Internal Gateway

.NET 10 analogue of the Java PoC gateway: custom middleware, shared YAML DSL,
JWT identity envelope, capability stubs, Kafka consume/fan-out with dedup /
rate-limit / retry.

## Run locally

```bash
cd dotnet-gateway
dotnet run --project src/InternalGateway.DotNet
```

Environment:

| Variable | Default |
|----------|---------|
| `GATEWAY_DSL_PATH` | `../dsl` |
| `DEPOSIT_OFFER_SERVICE_URL` | `http://localhost:8090` |
| `GATEWAY_ENVELOPE_SECRET` | PoC secret |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `GATEWAY_DISABLE_KAFKA` | unset / `true` for HTTP-only |
| `SERVER_PORT` | `8082` in Docker |

## Docker A/B

```bash
docker compose --profile dotnet-benchmark up -d --build kafka deposit-offer-service-dotnet dotnet-gateway
docker compose --profile dotnet-benchmark run --rm -e PROFILE=smoke k6-dotnet
```

Host port: **8082**.
