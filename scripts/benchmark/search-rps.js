import http from 'k6/http';import { check, sleep } from 'k6';import { Trend, Counter, Rate } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

/**
 * Internal Gateway RPS / latency overhead benchmark.
 *
 * Compares:
 *   - via_gateway:    POST /deposit-offers/search  (full PoC ingress path)
 *   - direct_backend: POST /internal/v1/offers/search (baseline)
 *   - capability:     GET  /internal/capabilities/... (optional gateway-only floor)
 *
 * Environment variables (all optional):
 *   GATEWAY_URL          default http://gateway:8080
 *   BACKEND_URL          default http://deposit-offer-service:8090
 *   GATEWAY_LABEL        label in report (default internal-gateway-poc)
 *   SUMMARY_FILE         JSON output path inside container (default /results/summary.json)
 *   PROFILE              smoke | stages | spike | custom  (default stages)
 *   VUS                  VU count for custom profile (default 10)
 *   DURATION             duration for custom/smoke (default 30s / 15s)
 *   INCLUDE_CAPABILITY   true|false (default false)
 *   THINK_TIME_MS        sleep between iterations in ms (default 0)
 *   P95_THRESHOLD_MS     fail if p95 via_gateway exceeds (default 2000)
 *   ERROR_RATE_THRESHOLD fail if error rate exceeds (default 0.01)
 */

const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://gateway:8080';
const BACKEND_URL = __ENV.BACKEND_URL || 'http://deposit-offer-service:8090';
const GATEWAY_LABEL = __ENV.GATEWAY_LABEL || 'internal-gateway-poc';
const SUMMARY_FILE = __ENV.SUMMARY_FILE || '/results/summary.json';
const PROFILE = (__ENV.PROFILE || 'stages').toLowerCase();
const INCLUDE_CAPABILITY = (__ENV.INCLUDE_CAPABILITY || 'false').toLowerCase() === 'true';
const THINK_TIME_MS = Number(__ENV.THINK_TIME_MS || '0');
const P95_THRESHOLD_MS = Number(__ENV.P95_THRESHOLD_MS || '2000');
const ERROR_RATE_THRESHOLD = Number(__ENV.ERROR_RATE_THRESHOLD || '0.01');

const viaGatewayLatency = new Trend('via_gateway_duration', true);
const directLatency = new Trend('direct_backend_duration', true);
const capabilityLatency = new Trend('capability_duration', true);
const viaGatewayCount = new Counter('via_gateway_samples');
const directCount = new Counter('direct_backend_samples');
const capabilityCount = new Counter('capability_samples');
const viaGatewayErrors = new Counter('via_gateway_errors');
const directErrors = new Counter('direct_backend_errors');
const errorRate = new Rate('benchmark_errors');

const PAYLOAD = JSON.stringify({
  organizationId: 'org-demo-001',
  accountId: 'acc-demo-001',
  amount: 500000,
  termMonths: 12,
});

// Backend only checks envelope is non-blank; JWT validation is gateway-side.
const DUMMY_ENVELOPE = 'benchmark-identity-envelope';

function buildOptions() {
  const thresholds = {
    via_gateway_duration: [`p(95)<${P95_THRESHOLD_MS}`],
    direct_backend_duration: [`p(95)<${P95_THRESHOLD_MS}`],
    benchmark_errors: [`rate<${ERROR_RATE_THRESHOLD}`],
    http_req_failed: [`rate<${ERROR_RATE_THRESHOLD}`],
  };

  const common = {
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(95)', 'p(99)'],
    thresholds,
  };

  if (PROFILE === 'smoke') {
    return {
      ...common,
      scenarios: {
        smoke: {
          executor: 'constant-vus',
          vus: Number(__ENV.VUS || '2'),
          duration: __ENV.DURATION || '15s',
        },
      },
    };
  }

  if (PROFILE === 'spike') {
    return {
      ...common,
      scenarios: {
        spike: {
          executor: 'ramping-vus',
          startVUs: 0,
          stages: [
            { duration: '10s', target: 5 },
            { duration: '20s', target: 100 },
            { duration: '20s', target: 100 },
            { duration: '10s', target: 5 },
            { duration: '10s', target: 0 },
          ],
        },
      },
    };
  }

  if (PROFILE === 'custom') {
    return {
      ...common,
      scenarios: {
        custom: {
          executor: 'constant-vus',
          vus: Number(__ENV.VUS || '10'),
          duration: __ENV.DURATION || '60s',
        },
      },
    };
  }

  // Default: warm-up + stages 10/20/30 (50 VU saturates the H2 PoC and inflates errors)
  return {
    ...common,
    scenarios: {
      stages: {
        executor: 'ramping-vus',
        startVUs: 0,
        stages: [
          { duration: '20s', target: 5 },   // warm-up
          { duration: '10s', target: 10 },
          { duration: '60s', target: 10 },
          { duration: '10s', target: 20 },
          { duration: '60s', target: 20 },
          { duration: '10s', target: 30 },
          { duration: '60s', target: 30 },
          { duration: '20s', target: 0 },
        ],
      },
    },
  };
}

export const options = buildOptions();

function requestViaGateway() {
  const res = http.post(`${GATEWAY_URL}/deposit-offers/search`, PAYLOAD, {
    headers: {
      'Content-Type': 'application/json',
      'X-Demo-Subject-Id': 'demo-user-001',
      'X-Demo-Organization-Id': 'org-demo-001',
    },
    tags: { path: 'via_gateway' },
  });

  viaGatewayLatency.add(res.timings.duration);
  viaGatewayCount.add(1);
  const ok = check(res, {
    'via_gateway status 200': (r) => r.status === 200,
    'via_gateway has offers': (r) => {
      try {
        const body = r.json();
        return Array.isArray(body.offers);
      } catch (_) {
        return false;
      }
    },
  });
  if (!ok) {
    viaGatewayErrors.add(1);
    errorRate.add(1);
  } else {
    errorRate.add(0);
  }
  return res;
}

function requestDirectBackend() {
  const res = http.post(`${BACKEND_URL}/internal/v1/offers/search`, PAYLOAD, {
    headers: {
      'Content-Type': 'application/json',
      'X-Identity-Envelope': DUMMY_ENVELOPE,
      'X-Correlation-Id': `bench-${__VU}-${__ITER}`,
    },
    tags: { path: 'direct_backend' },
  });

  directLatency.add(res.timings.duration);
  directCount.add(1);
  const ok = check(res, {
    'direct_backend status 200': (r) => r.status === 200,
    'direct_backend has offers': (r) => {
      try {
        const body = r.json();
        return Array.isArray(body.offers);
      } catch (_) {
        return false;
      }
    },
  });
  if (!ok) {
    directErrors.add(1);
    errorRate.add(1);
  } else {
    errorRate.add(0);
  }
  return res;
}

function requestCapability() {
  const res = http.get(
    `${GATEWAY_URL}/internal/capabilities/accounts/acc-demo-001/deposit-context`,
    { tags: { path: 'capability' } }
  );
  capabilityLatency.add(res.timings.duration);
  capabilityCount.add(1);
  const ok = check(res, {
    'capability status 200': (r) => r.status === 200,
  });
  errorRate.add(ok ? 0 : 1);
  return res;
}

export default function () {
  // Alternate paths so both get comparable sample counts under the same VU schedule.
  if (__ITER % 2 === 0) {
    requestViaGateway();
  } else {
    requestDirectBackend();
  }

  if (INCLUDE_CAPABILITY && __ITER % 10 === 0) {
    requestCapability();
  }

  if (THINK_TIME_MS > 0) {
    sleep(THINK_TIME_MS / 1000);
  }
}

function metricStats(data, name, countMetricName) {
  const m = data.metrics[name];
  if (!m || !m.values) {
    return null;
  }
  const v = m.values;
  const countMetric = countMetricName ? data.metrics[countMetricName] : null;
  const count = (countMetric && countMetric.values && countMetric.values.count) || v.count || 0;
  return {
    count,
    avg: v.avg,
    min: v.min,
    max: v.max,
    p50: v['p(50)'] != null ? v['p(50)'] : v.med,
    p95: v['p(95)'],
    p99: v['p(99)'],
  };
}

function round(n) {
  return n == null || Number.isNaN(n) ? null : Math.round(n * 100) / 100;
}

export function handleSummary(data) {
  const via = metricStats(data, 'via_gateway_duration', 'via_gateway_samples');
  const direct = metricStats(data, 'direct_backend_duration', 'direct_backend_samples');
  const capability = metricStats(data, 'capability_duration', 'capability_samples');
  const httpReqs = data.metrics.http_reqs ? data.metrics.http_reqs.values : {};
  const failed = data.metrics.http_req_failed ? data.metrics.http_req_failed.values : {};

  const overhead = via && direct
    ? {
        avg_ms: round(via.avg - direct.avg),
        p50_ms: round(via.p50 - direct.p50),
        p95_ms: round(via.p95 - direct.p95),
        p99_ms: round(via.p99 - direct.p99),
      }
    : null;

  const report = {
    generatedAt: new Date().toISOString(),
    profile: PROFILE,
    gatewayLabel: GATEWAY_LABEL,
    targets: {
      gatewayUrl: GATEWAY_URL,
      backendUrl: BACKEND_URL,
      includeCapability: INCLUDE_CAPABILITY,
    },
    throughput: {
      http_reqs: httpReqs.count || 0,
      rps: round(httpReqs.rate),
      failed_rate: failed.rate,
    },
    via_gateway: via
      ? {
          count: via.count,
          avg_ms: round(via.avg),
          p50_ms: round(via.p50),
          p95_ms: round(via.p95),
          p99_ms: round(via.p99),
          max_ms: round(via.max),
        }
      : null,
    direct_backend: direct
      ? {
          count: direct.count,
          avg_ms: round(direct.avg),
          p50_ms: round(direct.p50),
          p95_ms: round(direct.p95),
          p99_ms: round(direct.p99),
          max_ms: round(direct.max),
        }
      : null,
    capability: capability
      ? {
          count: capability.count,
          avg_ms: round(capability.avg),
          p50_ms: round(capability.p50),
          p95_ms: round(capability.p95),
          p99_ms: round(capability.p99),
          max_ms: round(capability.max),
        }
      : null,
    gateway_overhead: overhead,
    notes: [
      'via_gateway includes JWT/envelope creation and 2 capability callbacks from deposit-offer-service.',
      'gateway_overhead = via_gateway latency - direct_backend latency (same payload, matched load schedule).',
      'Custom gateway is Spring MVC/Tomcat; SCG contour is Spring Cloud Gateway WebFlux/Netty.',
      'Use identical PROFILE / host / JVM flags when comparing gateways.',
    ],
  };

  const lines = [
    '',
    `=== ${GATEWAY_LABEL} benchmark summary ===`,
    `Profile:          ${PROFILE}`,
    `Gateway label:    ${GATEWAY_LABEL}`,
    `HTTP RPS:         ${report.throughput.rps ?? 'n/a'}`,
    `Failed rate:      ${report.throughput.failed_rate != null ? (report.throughput.failed_rate * 100).toFixed(2) + '%' : 'n/a'}`,
    '',
    'via_gateway:',
    via
      ? `  count=${via.count}  avg=${round(via.avg)}ms  p50=${round(via.p50)}ms  p95=${round(via.p95)}ms  p99=${round(via.p99)}ms`
      : '  (no samples)',
    'direct_backend:',
    direct
      ? `  count=${direct.count}  avg=${round(direct.avg)}ms  p50=${round(direct.p50)}ms  p95=${round(direct.p95)}ms  p99=${round(direct.p99)}ms`
      : '  (no samples)',
  ];

  if (overhead) {
    lines.push(
      'gateway_overhead (via - direct):',
      `  avg=${overhead.avg_ms}ms  p50=${overhead.p50_ms}ms  p95=${overhead.p95_ms}ms  p99=${overhead.p99_ms}ms`
    );
  }

  if (capability) {
    lines.push(
      'capability (gateway-only floor):',
      `  count=${capability.count}  avg=${round(capability.avg)}ms  p50=${round(capability.p50)}ms  p95=${round(capability.p95)}ms`
    );
  }

  lines.push('', `JSON report written to ${SUMMARY_FILE} (when volume is mounted).`, '');

  const output = {
    stdout: lines.join('\n') + '\n' + textSummary(data, { indent: ' ', enableColors: false }),
  };
  output[SUMMARY_FILE] = JSON.stringify(report, null, 2);
  return output;
}
