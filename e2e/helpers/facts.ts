import fs from 'node:fs';
import path from 'node:path';

/**
 * Small, sanitized evidence file written beside Playwright's machine result.
 * It contains only fictional labels, UUIDs, booleans, counts and cent amounts.
 * Tokens, passwords, cookies, headers and callback URLs never enter this file.
 */
export interface JwtFacts {
  fingerprint: string;
  ageSeconds: number;
  expiresInSeconds: number;
  expired: boolean;
}

export interface Facts {
  run: {
    label: string;
    month: string;
    startedAt: string;
    browserVersion?: string;
    playwrightVersion: string;
  };
  checks: Record<string, unknown>;
  requests: Record<string, Record<string, unknown>>;
  identities: Record<string, JwtFacts & { identityId?: string }>;
  budgets: Record<string, Record<string, number>>;
  notifications: Record<string, Record<string, unknown>>;
  callback: Record<string, unknown>;
  limitations: string[];
}

const configuredFactsFile = process.env['P6_FACTS_FILE'];
if (configuredFactsFile === undefined || configuredFactsFile.length === 0) {
  throw new Error('P6_FACTS_FILE is required');
}
const factsFile: string = configuredFactsFile;

export const facts: Facts = {
  run: {
    label: process.env['P6_RUN_ID'] ?? 'unlabelled',
    month: new Date().toISOString().slice(0, 7),
    startedAt: new Date().toISOString(),
    playwrightVersion: '1.60.0',
  },
  checks: {},
  requests: {},
  identities: {},
  budgets: {},
  notifications: {},
  callback: {},
  limitations: [
    'Chromium only; this suite makes no cross-browser or availability claim.',
    'The runner owns a disposable local Compose stack; timings are fixture bounds, not an SLA.',
    'No trace, video, HAR or storage state is produced, so failure artefacts are intentionally not retained.',
    'The optional stale two-context edit journey is not part of this bounded critical suite.',
  ],
};

export function flushFacts(): void {
  fs.mkdirSync(path.dirname(factsFile), { recursive: true });
  fs.writeFileSync(factsFile, `${JSON.stringify(facts, null, 2)}\n`, 'utf8');
}

export function check(name: string, value: unknown): void {
  facts.checks[name] = value;
  flushFacts();
}
