import { buildBurstStressScenario1000 } from './k6_scaled_traffic_lib.js';

// Scenario A: 1000-line burst stress test
// - scope: line_id 1~1000, family_id 1~250
// - all groups run with line-internal burst(http.batch)
// - round interval: 1 second
const scenario = buildBurstStressScenario1000();

export const options = scenario.options;
export const setup = scenario.setup;
export default scenario.default;
