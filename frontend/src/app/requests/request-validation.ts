import { AbstractControl, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import { MAX_REQUEST_AMOUNT } from './request-api';

export const TITLE_MAX = 200;
export const JUSTIFICATION_MAX = 2000;
export const REJECTION_REASON_MAX = 1000;

export interface CanonicalAmount {
  /** Normalized decimal text with exactly two fraction digits. */
  readonly text: string;
  /** The JSON number sent to the API; no budget arithmetic is performed with it. */
  readonly value: number;
}

/**
 * Parses monetary text exactly.
 *
 * Accepts only 1-12 integer digits and at most two fraction digits. Values that cannot round-trip
 * through the supported JSON number range are rejected instead of being silently rounded.
 */
export function parseMoneyInput(raw: string): CanonicalAmount | null {
  const value = raw.trim();
  const match = /^(\d{1,12})(?:\.(\d{1,2}))?$/.exec(value);
  if (match === null) return null;
  const whole = match[1]!.replace(/^0+(?=\d)/, '') || '0';
  const fraction = (match[2] ?? '').padEnd(2, '0');
  const text = `${whole}.${fraction}`;
  const cents = BigInt(whole) * 100n + BigInt(fraction);
  if (cents <= 0n || cents > 99_999_999_999_999n) return null;
  const number = Number(text);
  const exactCents = Number(cents);
  if (
    !Number.isFinite(number) ||
    number <= 0 ||
    number > MAX_REQUEST_AMOUNT ||
    !Number.isSafeInteger(exactCents) ||
    Math.round(number * 100) !== exactCents
  ) {
    return null;
  }
  return { text, value: number };
}

export function formatMoneyInput(amount: number): string {
  const cents = Math.round(amount * 100);
  if (!Number.isSafeInteger(cents)) return '';
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`;
}

export function nonBlankValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value = control.value;
    return typeof value === 'string' && value.trim().length === 0 ? { blank: true } : null;
  };
}

export function moneyValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value = control.value;
    if (typeof value !== 'string' || value.trim().length === 0) return null;
    return parseMoneyInput(value) === null ? { money: true } : null;
  };
}

export const TITLE_VALIDATORS = [Validators.required, nonBlankValidator(), Validators.maxLength(TITLE_MAX)];
export const JUSTIFICATION_VALIDATORS = [
  Validators.required,
  nonBlankValidator(),
  Validators.maxLength(JUSTIFICATION_MAX),
];
export const AMOUNT_VALIDATORS = [Validators.required, moneyValidator()];
export const REASON_VALIDATORS = [
  Validators.required,
  nonBlankValidator(),
  Validators.maxLength(REJECTION_REASON_MAX),
];
