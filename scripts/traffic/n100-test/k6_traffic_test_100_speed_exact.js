import { buildSpeedExactScenario100 } from './k6_scaled_traffic_lib.js';

// Scenario B: speed exact test for G5 only
// - scope: line_id 77~88 (family_id 20~22)
// - sequential requests per line with 1-second start interval
// - validates exact deducted sum under app2 speed limit(1000Kbps)
const scenario = buildSpeedExactScenario100();

export const options = scenario.options;
export const setup = scenario.setup;
export default scenario.default;
