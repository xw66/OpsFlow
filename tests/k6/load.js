import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const fixture = JSON.parse(open('/results/fixture.json'));
const mode = __ENV.MODE || 'list';
const coldIds = mode === 'cold' ? JSON.parse(open('/results/cold-ids.json')) : [];
const vus = Number(__ENV.VUS || 10);
const label = (__ENV.RUN_LABEL || 'baseline').replace(/[^a-zA-Z0-9_-]/g, '_');
const succeeded = new Counter('business_success');
const conflicts = new Counter('expected_conflicts');
const elapsed = new Trend('iteration_elapsed_ms', true);
const transportErrors = new Counter('transport_errors');
const scenario = mode === 'race'
  ? { executor: 'per-vu-iterations', vus, iterations: 1, maxDuration: '1m' }
  : mode === 'seed' || mode === 'cold'
    ? { executor: 'shared-iterations', vus, iterations: mode === 'cold' ? coldIds.length : Number(__ENV.ITERATIONS || 1000), maxDuration: '10m' }
    : { executor: 'constant-vus', vus, duration: __ENV.DURATION || '30s', gracefulStop: '10s' };

export const options = {
  scenarios: { workload: scenario },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    ...(mode === 'race' ? { business_success: ['count==1'], expected_conflicts: [`count==${vus - 1}`] } : {}),
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};
if (mode === 'race') http.setResponseCallback(http.expectedStatuses(200, 409));

export default function () {
  const started = Date.now();
  const actor = mode === 'race' ? fixture.users.agent : fixture.users.owner;
  const params = { headers: { Authorization: `Bearer ${actor.token}`, 'Content-Type': 'application/json' }, tags: { name: mode }, timeout: '10s' };
  let response;
  if (mode === 'create' || mode === 'seed') {
    // 每次迭代生成不同业务请求键，避免把幂等命中伪装为创建吞吐量。
    params.headers['Idempotency-Key'] = `bench_${fixture.runId}_${label}_${exec.scenario.iterationInTest}`;
    response = http.post(`${fixture.baseUrl}/api/tickets`, JSON.stringify({
      title: `压测${label}-${exec.scenario.iterationInTest}`, description: '企业内部IT问题流转压力测试数据',
      categoryId: fixture.categoryId, priority: 'HIGH', tags: ['benchmark'],
    }), params);
    const valid = response.status === 201 && response.json('data.ticket.status') === 'CREATED';
    check(response, { '创建真实工单': () => valid });
    succeeded.add(valid ? 1 : 0);
  } else if (mode === 'race') {
    response = http.post(`${fixture.baseUrl}/api/tickets/${fixture.raceId}/accept`, JSON.stringify({ version: fixture.raceVersion, reason: 'k6并发接单' }), params);
    succeeded.add(response.status === 200 ? 1 : 0);
    conflicts.add(response.status === 409 ? 1 : 0);
    check(response, { '接单成功或预期版本冲突': r => r.status === 200 || r.status === 409 });
  } else if (mode === 'detail' || mode === 'cold') {
    const id = mode === 'cold' ? coldIds[exec.scenario.iterationInTest] : fixture.detailId;
    response = http.get(`${fixture.baseUrl}/api/tickets/${id}`, params);
    check(response, { '详情归属正确': r => r.status === 200 && r.json('data.ticket.id') === id });
  } else if (mode === 'list') {
    response = http.get(`${fixture.baseUrl}/api/tickets?limit=20&offset=${Number(__ENV.OFFSET || 0)}`, params);
    check(response, { '列表仅返回本人数据': r => r.status === 200 && r.json('data').every(t => t.userId === actor.id) });
  } else { throw new Error(`未知模式${mode}`); }
  // 额外记录含客户端等待的总时长，避免传输超时缺少HTTP分段时间时被误读为快速请求。
  elapsed.add(Date.now() - started);
  transportErrors.add(response.status === 0 ? 1 : 0, { code: String(response.error_code || 0) });
}

export function handleSummary(data) {
  const result = { mode, label, vus, scenario, recordedAt: new Date().toISOString(), metrics: data.metrics, state: data.state };
  return { [`/results/${mode}-${label}.json`]: JSON.stringify(result, null, 2), stdout: `${mode}/${label}: ${JSON.stringify(data.metrics.http_req_duration?.values)}\n` };
}
