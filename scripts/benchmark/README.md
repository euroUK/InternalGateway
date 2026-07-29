# Benchmark: RPS and gateway latency overhead

Measures throughput and latency of the Internal Gateway PoC versus a direct
call to `deposit-offer-service`, so results can later be compared with
Spring Cloud Gateway under the same load profile.

## What is measured

| Tag | Request | Purpose |
|-----|---------|---------|
| `via_gateway` | `POST http://gateway:8080/deposit-offers/search` | Full PoC ingress path |
| `direct_backend` | `POST http://deposit-offer-service:8090/internal/v1/offers/search` | Backend baseline |
| `capability` (optional) | `GET .../internal/capabilities/accounts/.../deposit-context` | Gateway-only latency floor |

Fixed payload (same as demo UI):

```json
{"organizationId":"org-demo-001","accountId":"acc-demo-001","amount":500000,"termMonths":12}
```

**Important:** `via_gateway` is not a pure reverse-proxy hop. The ingress path
creates a JWT identity envelope and `deposit-offer-service` performs **two**
capability callbacks back to the gateway. Overhead therefore reflects the real
PoC architecture:

```
gateway_overhead = latency(via_gateway) − latency(direct_backend)
```

## Prerequisites

1. Docker Compose stack is up and healthy:

```bash
docker compose up -d
docker compose ps
```

2. Results directory exists (created automatically by Compose volume mount):

```bash
mkdir -p scripts/benchmark/results
```

## Run

### Smoke (quick sanity, ~15s)

```bash
docker compose --profile benchmark run --rm -e PROFILE=smoke k6
```

### Default stages (warm-up → 10 / 20 / 30 VUs, ~4 min)

```bash
docker compose --profile benchmark run --rm k6
```

> Peak 30 VUs keeps the H2 PoC stable. Use `PROFILE=spike` (up to 100 VUs) only to observe saturation, not for overhead comparison.

### Spike

```bash
docker compose --profile benchmark run --rm -e PROFILE=spike k6
```

### Custom duration / VUs

```bash
docker compose --profile benchmark run --rm \
  -e PROFILE=custom \
  -e VUS=25 \
  -e DURATION=90s \
  k6
```

### Include capability probe

```bash
docker compose --profile benchmark run --rm \
  -e PROFILE=smoke \
  -e INCLUDE_CAPABILITY=true \
  k6
```

### Host-installed k6 (optional)

Expose backend first (Compose does not publish `8090` by default), or point
`BACKEND_URL` at a reachable address:

```bash
k6 run \
  -e GATEWAY_URL=http://localhost:8080 \
  -e BACKEND_URL=http://localhost:8090 \
  -e PROFILE=smoke \
  scripts/benchmark/search-rps.js
```

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PROFILE` | `stages` | `smoke` \| `stages` \| `spike` \| `custom` |
| `GATEWAY_URL` | `http://gateway:8080` | Gateway base URL |
| `BACKEND_URL` | `http://deposit-offer-service:8090` | Direct backend URL |
| `GATEWAY_LABEL` | `internal-gateway-poc` | Label in JSON/console report |
| `SUMMARY_FILE` | `/results/summary.json` | Output path inside k6 container |
| `VUS` | `10` (`2` for smoke) | Virtual users for `smoke` / `custom` |
| `DURATION` | `30s` (`15s` smoke) | Duration for `smoke` / `custom` |
| `INCLUDE_CAPABILITY` | `false` | Also sample capability GET |
| `THINK_TIME_MS` | `0` | Sleep between iterations |
| `P95_THRESHOLD_MS` | `2000` | Fail run if p95 exceeds |
| `ERROR_RATE_THRESHOLD` | `0.01` | Fail run if error rate exceeds |

## Outputs

After each run:

1. Console summary with RPS, p50/p95/p99 for both paths, and `gateway_overhead`.
2. JSON report under `scripts/benchmark/results/` (`summary-custom.json` / `summary-scg.json` by default).

Example fields:

```json
{
  "gatewayLabel": "spring-cloud-gateway-webflux",
  "throughput": { "rps": 42.5, "failed_rate": 0 },
  "via_gateway": { "p50_ms": 45, "p95_ms": 120, "p99_ms": 180 },
  "direct_backend": { "p50_ms": 20, "p95_ms": 55, "p99_ms": 80 },
  "gateway_overhead": { "p50_ms": 25, "p95_ms": 65, "p99_ms": 100 }
}
```

Cross-check gateway-side averages after a run:

```bash
curl -s http://localhost:8080/internal/admin/stats
curl -s http://localhost:8081/internal/admin/stats
```

Optional request samples:

```bash
curl -s "http://localhost:8080/internal/admin/requests?limit=50"
curl -s "http://localhost:8081/internal/admin/requests?limit=50"
```

## Fair comparison with Spring Cloud Gateway (WebFlux)

Both gateways execute the same benchmark path from
`dsl/deposit-offers-gateway.dsl.yaml` (compiled by shared `gateway-dsl-core`):

- `POST /deposit-offers/search` → `deposit-offer-service` `/internal/v1/offers/search`
- JWT identity envelope + business-control stub
- two capability HTTP callbacks answered from DSL static templates

Admin hot-reload (without restart):

```bash
curl -s http://localhost:8080/internal/admin/dsl/config
curl -s -X POST http://localhost:8080/internal/admin/dsl/reload
curl -s http://localhost:8081/internal/admin/dsl/config
curl -s -X POST http://localhost:8081/internal/admin/dsl/reload
curl -s http://localhost:8082/internal/admin/dsl/config
curl -s -X POST http://localhost:8082/internal/admin/dsl/reload
```

The repo includes an isolated SCG contour (profile `scg-benchmark`) and a .NET contour (profile `dotnet-benchmark`):

| Service | Port / URL | Role |
|---------|------------|------|
| `scg-gateway` | host `8081` | Spring Cloud Gateway WebFlux + DSL RouteDefinitions + capability stubs |
| `deposit-offer-service-scg` | internal `8090` | Dedicated backend; callbacks go to `scg-gateway:8081` |
| `k6-scg` | — | Same k6 script, separate summary file |
| `dotnet-gateway` | host `8082` | ASP.NET Core 10 middleware gateway + Kafka fan-out |
| `deposit-offer-service-dotnet` | internal `8090` | Dedicated backend; callbacks go to `dotnet-gateway:8082` |
| `k6-dotnet` | — | Same k6 script → `summary-dotnet.json` |

### Prepare identical data contours

Before measuring, start from clean local catalog volumes and avoid Kafka /
processor demo traffic on the custom stack during the run:

```bash
# Optional: wipe local H2 catalogs so both contours seed the same demo data
docker compose --profile scg-benchmark down
docker compose down
docker volume rm -f internalgateway_offer-service-data internalgateway_offer-service-scg-data 2>/dev/null || true
docker volume rm -f offer-service-data offer-service-scg-data 2>/dev/null || true

# Custom HTTP path only (no frontend / processor demos required for search RPS)
docker compose up -d kafka deposit-offer-service test-processor gateway

# Isolated SCG contour (no Kafka fan-out path)
docker compose --profile scg-benchmark up -d deposit-offer-service-scg scg-gateway

# Isolated .NET contour (Kafka shared with main stack)
docker compose --profile dotnet-benchmark up -d kafka deposit-offer-service-dotnet dotnet-gateway
```

Do **not** run `scripts/test-resilience.ps1`, processor seed publishes, or UI
Kafka demos while collecting A/B numbers.

```bash
# Smoke both gateways with the same PROFILE
docker compose --profile benchmark run --rm -e PROFILE=smoke k6
docker compose --profile scg-benchmark run --rm -e PROFILE=smoke k6-scg
docker compose --profile dotnet-benchmark run --rm -e PROFILE=smoke k6-dotnet

# Primary comparison (20 VU / 45s)
docker compose --profile benchmark run --rm \
  -e PROFILE=custom -e VUS=20 -e DURATION=45s \
  -e SUMMARY_FILE=/results/summary-custom.json k6
docker compose --profile scg-benchmark run --rm \
  -e PROFILE=custom -e VUS=20 -e DURATION=45s \
  -e SUMMARY_FILE=/results/summary-scg.json k6-scg
docker compose --profile dotnet-benchmark run --rm \
  -e PROFILE=custom -e VUS=20 -e DURATION=45s \
  -e SUMMARY_FILE=/results/summary-dotnet.json k6-dotnet
```

Results:

- Custom: `scripts/benchmark/results/summary-custom.json`
- SCG: `scripts/benchmark/results/summary-scg.json`
- .NET: `scripts/benchmark/results/summary-dotnet.json`

**Runtime caveat:** custom gateway is Spring MVC/Tomcat; SCG is WebFlux/Netty;
.NET is Kestrel + custom middleware. All implement the same HTTP critical path
(JWT envelope + proxy + 2 capability callbacks), but the I/O model differs.

Keep these identical across runs:

| Factor | Rule |
|--------|------|
| Host / VM | Same machine or matched specs |
| Docker / JVM | Same CPU/memory limits and JVM flags |
| Replicas | Same number of gateway instances |
| Catalog data | Clean volumes / same local seed; no Kafka seed during measurement |
| Warm-up | Always progress JVM + H2 before measuring |
| Payload | Same JSON body and headers contract |
| k6 profile | Same `PROFILE`, VUs, duration, thresholds |
| Background load | No concurrent Kafka/processor demos during SCG A/B |
| DSL | Same `deposit-offers-gateway.dsl.yaml` mounted into both gateways |

Report columns:

| Scenario | RPS | p50 | p95 | p99 | Error % | Overhead vs direct |
|----------|-----|-----|-----|-----|---------|--------------------|
| Direct backend (custom contour) | | | | | | baseline |
| Internal Gateway PoC (MVC) | | | | | | |
| Direct backend (SCG contour) | | | | | | baseline |
| Spring Cloud Gateway (WebFlux) | | | | | | |

Recommended sequence:

1. Reset volumes and start both contours as above.
2. `PROFILE=smoke` on both `k6` and `k6-scg`.
3. Identical `PROFILE=custom` (`VUS=20`, `DURATION=45s`) on both.
4. Optionally `INCLUDE_CAPABILITY=true` for a gateway-only latency floor.
5. Compare `summary-custom.json` vs `summary-scg.json` and `/internal/admin/stats` on `:8080` / `:8081`.
6. Confirm both `/internal/admin/dsl/config` report the same route/capability counts.

## Enriched deposit quote (parity smoke)

Separate from k6 search load: `POST /deposit-offers/enriched` runs org capability
loopback → JWT → `POST /internal/v1/offers/fixed` → mapped client result.

```bash
BODY='{"organizationId":"org-demo-001","accountId":"acc-demo-001","amount":500000,"termMonths":12}'
curl -sS -X POST http://localhost:8080/deposit-offers/enriched -H 'Content-Type: application/json' -d "$BODY"
curl -sS -X POST http://localhost:8081/deposit-offers/enriched -H 'Content-Type: application/json' -d "$BODY"
curl -sS -X POST http://localhost:8082/deposit-offers/enriched -H 'Content-Type: application/json' -d "$BODY"
```

Expect the same JSON shape on all three ports (`organizationDisplayName`, nested
`offer`, `correlationId`); only `correlationId` differs per call.

## Limitations

- Single gateway replica, H2 backend, no production tuning — absolute RPS is a PoC signal, not capacity planning.
- In-memory `/internal/admin/stats` averages are coarser than k6 percentiles; prefer k6 for p95/p99.
- Kafka fan-out / rate-limit scenarios are **not** part of this HTTP RPS test (`scripts/test-resilience.ps1` covers those separately).
- SCG contour uses local catalog seed only (no Kafka fan-in); critical-path hops remain equivalent.
