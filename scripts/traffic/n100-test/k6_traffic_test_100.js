import { buildBurstStressScenario100 } from './k6_scaled_traffic_lib.js';

// Scenario A: 100-line burst stress test
// - scope: line_id 1~100, family_id 1~25
// - all groups run with line-internal burst(http.batch)
// - round interval: 1 second
const scenario = buildBurstStressScenario100();

export const options = scenario.options;
export const setup = scenario.setup;
export default scenario.default;
