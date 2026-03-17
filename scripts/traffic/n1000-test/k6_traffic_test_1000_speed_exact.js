import { buildSpeedExactScenario1000 } from './k6_scaled_traffic_lib.js';

// Scenario B: speed exact test for G5 only
// - scope: line_id 761~880 (family_id 191~220)
// - sequential requests per line with 1-second start interval
// - validates exact deducted sum under app2 speed limit(1000Kbps)
const scenario = buildSpeedExactScenario1000();

export const options = scenario.options;
export const setup = scenario.setup;
export default scenario.default;
