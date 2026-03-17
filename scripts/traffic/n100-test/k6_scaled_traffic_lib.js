import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const MB = 1024 * 1024;
const TOTAL_ATTEMPT_MB_PER_LINE = 50;
const REQUEST_INTERVAL_SECONDS = 1;
const SPEED_LIMIT_BYTES_PER_SECOND = 125000; // 1000Kbps = 125000B/s

const LINE_START = 1;
const LINE_END = 100;
const SPEED_GROUP_LINE_START = 77;
const SPEED_GROUP_LINE_END = 88;

const BURST_WIDTH = Math.max(1, Number(__ENV.BURST_WIDTH || 3));
const K6_TIMEOUT = __ENV.REQUEST_TIMEOUT || '30s';
const DEBUG_NON_200 = String(__ENV.DEBUG_NON_200 || 'false').toLowerCase() === 'true';
const MAX_DEBUG_LOGS_PER_VU = Number(__ENV.MAX_DEBUG_LOGS_PER_VU || 5);

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const API_PATH = (__ENV.API_PATH || '/api/traffic/requests').startsWith('/')
  ? (__ENV.API_PATH || '/api/traffic/requests')
  : `/${__ENV.API_PATH}`;
const TARGET_URL = `${BASE_URL}${API_PATH}`;

// k6 custom metrics shared by both scenarios.
const enqueueDurationMs = new Trend('traffic_enqueue_duration_ms');
const enqueueHttpErrorRate = new Rate('traffic_enqueue_http_error_rate');
const enqueueTraceIdErrorRate = new Rate('traffic_enqueue_traceid_error_rate');
const enqueueSuccessCount = new Counter('traffic_enqueue_success_total');
const enqueueFailCount = new Counter('traffic_enqueue_fail_total');
const attemptedBytesCount = new Counter('traffic_attempted_bytes_total');

const GROUPS = [
  { name: 'G1_NO_RESTRICTION', lineStart: 1, lineEnd: 20, appId: 1 },
  { name: 'G2_LINE_DAILY_20MB', lineStart: 21, lineEnd: 40, appId: 1 },
  { name: 'G3_APP2_DAILY_5MB', lineStart: 41, lineEnd: 60, appId: 2 },
  { name: 'G4_SHARED_ONLY_APP3', lineStart: 61, lineEnd: 76, appId: 3 },
  { name: 'G5_APP2_SPEED_1MBPS', lineStart: 77, lineEnd: 88, appId: 2 },
  { name: 'G6_APP4_DAILY_8MB', lineStart: 89, lineEnd: 100, appId: 4 },
];

function createDeterministicRandom(seed) {
  let state = seed >>> 0;
  return () => {
    state = (1664525 * state + 1013904223) >>> 0;
    return state / 4294967296;
  };
}

// Each line always gets the same 1~3MB chunk sequence and total exactly 50MB.
function buildChunkPlanMb(lineId, totalMb) {
  const random = createDeterministicRandom((lineId * 1103515245 + 12345) >>> 0);
  const chunks = [];
  let remaining = totalMb;

  while (remaining > 0) {
    if (remaining <= 3) {
      chunks.push(remaining);
      remaining = 0;
      continue;
    }

    const nextChunkMb = 1 + Math.floor(random() * 3);
    chunks.push(nextChunkMb);
    remaining -= nextChunkMb;
  }

  return chunks;
}

function resolveFamilyId(lineId) {
  if (lineId < LINE_START || lineId > LINE_END) {
    throw new Error(`lineId out of range: ${lineId}`);
  }
  return Math.floor((lineId - 1) / 4) + 1;
}

function resolveGroup(lineId) {
  const group = GROUPS.find((candidate) => lineId >= candidate.lineStart && lineId <= candidate.lineEnd);
  if (!group) {
    throw new Error(`group not found for lineId: ${lineId}`);
  }
  return group;
}

function buildLinePlan(lineId) {
  const group = resolveGroup(lineId);
  const chunksMb = buildChunkPlanMb(lineId, TOTAL_ATTEMPT_MB_PER_LINE);
  const chunksBytes = chunksMb.map((value) => value * MB);

  return {
    lineId,
    familyId: resolveFamilyId(lineId),
    groupName: group.name,
    appId: group.appId,
    chunksMb,
    chunksBytes,
    totalAttemptBytes: chunksBytes.reduce((sum, value) => sum + value, 0),
  };
}

function buildLinePlans(lineStart, lineEnd) {
  const plans = [];
  for (let lineId = lineStart; lineId <= lineEnd; lineId += 1) {
    plans.push(buildLinePlan(lineId));
  }
  return plans;
}

function buildExpectedHint(plan, scenarioType) {
  if (plan.groupName === 'G1_NO_RESTRICTION') {
    return `expected_deducted=${50 * MB}B`;
  }
  if (plan.groupName === 'G2_LINE_DAILY_20MB') {
    return `expected_deducted=${20 * MB}B`;
  }
  if (plan.groupName === 'G3_APP2_DAILY_5MB') {
    return `expected_deducted=${5 * MB}B`;
  }
  if (plan.groupName === 'G6_APP4_DAILY_8MB') {
    return `expected_deducted=${8 * MB}B`;
  }
  if (plan.groupName === 'G4_SHARED_ONLY_APP3') {
    return 'expected_line=family_aggregate_only';
  }

  if (String(scenarioType).startsWith('SPEED_EXACT')) {
    const speedExact = plan.chunksBytes.reduce(
      (sum, chunkBytes) => sum + Math.min(chunkBytes, SPEED_LIMIT_BYTES_PER_SECOND),
      0,
    );
    return `expected_deducted=${speedExact}B(speed_exact)`;
  }

  return `expected_group_behavior=speed_hit_expected cap_per_req=${SPEED_LIMIT_BYTES_PER_SECOND}B`;
}

function printScenarioPlan(scenarioLabel, linePlans) {
  const totalRequests = linePlans.reduce((sum, plan) => sum + plan.chunksBytes.length, 0);
  const totalAttemptBytes = linePlans.reduce((sum, plan) => sum + plan.totalAttemptBytes, 0);

  console.log(`[k6] scenario=${scenarioLabel} target_url=${TARGET_URL}`);
  console.log(`[k6] line_count=${linePlans.length} total_requests=${totalRequests} total_attempt_bytes=${totalAttemptBytes}`);
  console.log(`[k6] burst_width=${BURST_WIDTH} request_interval_seconds=${REQUEST_INTERVAL_SECONDS}`);
  console.log('[k6] line request plan (pre-recorded):');

  for (const plan of linePlans) {
    console.log(
      `[k6-plan] line=${plan.lineId} family=${plan.familyId} group=${plan.groupName} app=${plan.appId} `
      + `chunks_mb=[${plan.chunksMb.join(',')}] total_attempt_bytes=${plan.totalAttemptBytes} `
      + `${buildExpectedHint(plan, scenarioLabel)}`,
    );
  }
}

function buildTags(plan, scenarioName, requestIndex, roundIndex, burstSlot) {
  return {
    scenario: scenarioName,
    line_id: String(plan.lineId),
    family_id: String(plan.familyId),
    group: plan.groupName,
    app_id: String(plan.appId),
    request_index: String(requestIndex),
    round_index: String(roundIndex),
    burst_slot: String(burstSlot),
  };
}

function parseTraceId(response) {
  try {
    const body = response.json();
    return body && typeof body.traceId === 'string' && body.traceId.length > 0;
  } catch (error) {
    return false;
  }
}

function applyResponseMetrics(response, tags, debugState) {
  enqueueDurationMs.add(response.timings.duration, tags);

  const isHttpOk = check(response, {
    'enqueue status is 200': (res) => res.status === 200,
  }, tags);

  enqueueHttpErrorRate.add(!isHttpOk, tags);

  let hasTraceId = false;
  if (isHttpOk) {
    hasTraceId = parseTraceId(response);
  }
  enqueueTraceIdErrorRate.add(!hasTraceId, tags);

  if (isHttpOk && hasTraceId) {
    enqueueSuccessCount.add(1, tags);
  } else {
    enqueueFailCount.add(1, tags);
  }

  if (!isHttpOk && DEBUG_NON_200 && debugState.count < MAX_DEBUG_LOGS_PER_VU) {
    const bodyPreview = (response.body || '').replace(/\s+/g, ' ').slice(0, 240);
    console.error(
      `[k6-non200] scenario=${tags.scenario} line=${planSafeTag(tags.line_id)} family=${planSafeTag(tags.family_id)} `
      + `group=${tags.group} app=${tags.app_id} req_idx=${tags.request_index} status=${response.status} body="${bodyPreview}"`,
    );
    debugState.count += 1;
  }
}

function planSafeTag(value) {
  if (value === null || value === undefined) {
    return 'unknown';
  }
  return String(value);
}

function buildPayload(plan, requestBytes) {
  return JSON.stringify({
    lineId: plan.lineId,
    familyId: plan.familyId,
    appId: plan.appId,
    apiTotalData: requestBytes,
  });
}

const BURST_LINE_PLANS = buildLinePlans(LINE_START, LINE_END);
const SPEED_EXACT_LINE_PLANS = buildLinePlans(SPEED_GROUP_LINE_START, SPEED_GROUP_LINE_END);

export function buildBurstStressScenario100() {
  const scenarioName = 'BURST_STRESS_100';

  const options = {
    scenarios: {
      traffic_policy_burst_100: {
        executor: 'per-vu-iterations',
        vus: BURST_LINE_PLANS.length,
        iterations: 1,
        maxDuration: __ENV.MAX_DURATION || '90m',
        gracefulStop: '0s',
      },
    },
    thresholds: {
      traffic_enqueue_http_error_rate: ['rate==0'],
      traffic_enqueue_traceid_error_rate: ['rate==0'],
    },
  };

  function setup() {
    printScenarioPlan(scenarioName, BURST_LINE_PLANS);
    return { targetUrl: TARGET_URL };
  }

  function defaultFn(data) {
    const vuId = exec.vu.idInTest;
    const planIndex = vuId - 1;
    if (planIndex < 0 || planIndex >= BURST_LINE_PLANS.length) {
      return;
    }

    const plan = BURST_LINE_PLANS[planIndex];
    const debugState = { count: 0 };

    for (let chunkIndex = 0; chunkIndex < plan.chunksBytes.length; chunkIndex += BURST_WIDTH) {
      const roundStartAt = Date.now();
      const roundChunks = plan.chunksBytes.slice(chunkIndex, chunkIndex + BURST_WIDTH);
      const roundIndex = Math.floor(chunkIndex / BURST_WIDTH) + 1;

      const batchRequests = [];
      const requestTags = [];

      for (let slot = 0; slot < roundChunks.length; slot += 1) {
        const requestBytes = roundChunks[slot];
        const requestIndex = chunkIndex + slot + 1;
        const tags = buildTags(plan, scenarioName, requestIndex, roundIndex, slot + 1);

        attemptedBytesCount.add(requestBytes, tags);
        requestTags.push(tags);

        batchRequests.push([
          'POST',
          data.targetUrl,
          buildPayload(plan, requestBytes),
          {
            headers: { 'Content-Type': 'application/json' },
            tags,
            timeout: K6_TIMEOUT,
          },
        ]);
      }

      const responses = http.batch(batchRequests);

      for (let i = 0; i < responses.length; i += 1) {
        applyResponseMetrics(responses[i], requestTags[i], debugState);
      }

      if (chunkIndex + BURST_WIDTH < plan.chunksBytes.length) {
        const elapsedMs = Date.now() - roundStartAt;
        const sleepSeconds = Math.max(0, (REQUEST_INTERVAL_SECONDS * 1000 - elapsedMs) / 1000);
        sleep(sleepSeconds);
      }
    }
  }

  return {
    options,
    setup,
    default: defaultFn,
  };
}

export function buildSpeedExactScenario100() {
  const scenarioName = 'SPEED_EXACT_100';

  const options = {
    scenarios: {
      traffic_speed_exact_100: {
        executor: 'per-vu-iterations',
        vus: SPEED_EXACT_LINE_PLANS.length,
        iterations: 1,
        maxDuration: __ENV.MAX_DURATION || '60m',
        gracefulStop: '0s',
      },
    },
    thresholds: {
      traffic_enqueue_http_error_rate: ['rate==0'],
      traffic_enqueue_traceid_error_rate: ['rate==0'],
    },
  };

  function setup() {
    printScenarioPlan(scenarioName, SPEED_EXACT_LINE_PLANS);
    return { targetUrl: TARGET_URL };
  }

  function defaultFn(data) {
    const vuId = exec.vu.idInTest;
    const planIndex = vuId - 1;
    if (planIndex < 0 || planIndex >= SPEED_EXACT_LINE_PLANS.length) {
      return;
    }

    const plan = SPEED_EXACT_LINE_PLANS[planIndex];
    const debugState = { count: 0 };

    for (let i = 0; i < plan.chunksBytes.length; i += 1) {
      const requestStartAt = Date.now();
      const requestBytes = plan.chunksBytes[i];
      const tags = buildTags(plan, scenarioName, i + 1, i + 1, 1);

      attemptedBytesCount.add(requestBytes, tags);

      const response = http.post(data.targetUrl, buildPayload(plan, requestBytes), {
        headers: { 'Content-Type': 'application/json' },
        tags,
        timeout: K6_TIMEOUT,
      });

      applyResponseMetrics(response, tags, debugState);

      if (i < plan.chunksBytes.length - 1) {
        const elapsedMs = Date.now() - requestStartAt;
        const sleepSeconds = Math.max(0, (REQUEST_INTERVAL_SECONDS * 1000 - elapsedMs) / 1000);
        sleep(sleepSeconds);
      }
    }
  }

  return {
    options,
    setup,
    default: defaultFn,
  };
}
