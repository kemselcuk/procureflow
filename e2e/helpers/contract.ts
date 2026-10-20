import fs from 'node:fs';
import path from 'node:path';

/**
 * Single source of truth for the critical browser test identities.
 *
 * The Python runner loads the same JSON file to reject missing, skipped, duplicated or
 * unexpected results. Keeping the list here prevents the verifier and the spec from drifting
 * into two independent "expected test" definitions.
 */
export interface CriticalTestDefinition {
  readonly id: string;
  readonly title: string;
  readonly checks: readonly string[];
}

interface CriticalTestContract {
  readonly schemaVersion: number;
  readonly tests: readonly CriticalTestDefinition[];
}

const contractPath = path.join(__dirname, '..', 'critical-tests.json');
const parsed = JSON.parse(fs.readFileSync(contractPath, 'utf8')) as CriticalTestContract;

if (parsed.schemaVersion !== 1 || !Array.isArray(parsed.tests) || parsed.tests.length !== 7) {
  throw new Error('The critical test contract is missing, malformed or does not declare seven journeys');
}

export const CRITICAL_TESTS: readonly CriticalTestDefinition[] = parsed.tests;

const byId = new Map(CRITICAL_TESTS.map((entry) => [entry.id, entry]));

export function criticalTitle(id: string): string {
  const entry = byId.get(id);
  if (entry === undefined) throw new Error(`Unknown critical test identity: ${id}`);
  return entry.title;
}
