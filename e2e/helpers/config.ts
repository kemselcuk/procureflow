import { createHash } from 'node:crypto';

export const BASE_URL = required('P6_BASE_URL').replace(/\/$/, '');
export const RUN_ID = required('P6_RUN_ID');
export const DEMO_PASSWORD = required('P6_DEMO_PASSWORD');

const DEMO_ACCOUNTS = {
  employee: 'a-employee',
  manager: 'a-manager',
  finance: 'a-finance',
  admin: 'a-admin',
  foreignMember: 'b-employee',
} as const;

export type Account = keyof typeof DEMO_ACCOUNTS;
export function username(account: Account): string {
  return DEMO_ACCOUNTS[account];
}

/** Unique within a run and deterministic for that fixture label. */
export function label(suffix: string): string {
  return `P6 ${RUN_ID} ${suffix}`;
}

export function currentUtcMonth(now = new Date()): string {
  return `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, '0')}`;
}

export function cents(amount: number): number {
  return Math.round(amount * 100);
}

export function jwtFacts(token: string): {
  fingerprint: string;
  ageSeconds: number;
  expiresInSeconds: number;
  expired: boolean;
} {
  const parts = token.split('.');
  if (parts.length !== 3 || parts[1] === undefined) throw new Error('The captured bearer value was not a JWT');
  const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8')) as {
    iat?: unknown;
    exp?: unknown;
  };
  if (typeof payload.iat !== 'number' || typeof payload.exp !== 'number') {
    throw new Error('The captured JWT did not contain numeric iat/exp claims');
  }
  const now = Math.floor(Date.now() / 1000);
  return {
    fingerprint: createHash('sha256').update(token).digest('hex').slice(0, 16),
    ageSeconds: now - payload.iat,
    expiresInSeconds: payload.exp - now,
    expired: payload.exp <= now,
  };
}

function required(name: string): string {
  const value = process.env[name];
  if (value === undefined || value.length === 0) throw new Error(`${name} is required`);
  return value;
}
