import { RequestStatus } from '../requests/request-api';

const AMOUNT = new Intl.NumberFormat('en-IE', {
  style: 'currency',
  currency: 'EUR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

const INSTANT = new Intl.DateTimeFormat('en-GB', {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
  timeZone: 'UTC',
  timeZoneName: 'short',
});

/** EUR presentation only; budget authority is never calculated in the browser. */
export function formatAmount(amount: number): string {
  return AMOUNT.format(amount);
}

export function formatInstant(value: string | null | undefined): string {
  if (value === null || value === undefined || value.length === 0) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'Unknown time' : INSTANT.format(date);
}

export const REQUEST_STATUS_LABELS: Readonly<Record<RequestStatus, string>> = {
  DRAFT: 'Draft',
  AWAITING_DEPARTMENT_APPROVAL: 'Awaiting department approval',
  AWAITING_FINANCE_APPROVAL: 'Awaiting finance approval',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
};

export function statusLabel(status: string): string {
  return Object.hasOwn(REQUEST_STATUS_LABELS, status)
    ? REQUEST_STATUS_LABELS[status as RequestStatus]
    : humanize(status);
}

export const HISTORY_ACTION_LABELS: Readonly<Record<string, string>> = {
  'request.created': 'Draft created',
  'request.edited': 'Draft edited',
  'request.submitted': 'Submitted for approval',
  'request.department_approved': 'Department approved',
  'request.approved': 'Final approval recorded',
  'request.rejected': 'Request rejected',
  'request.cancelled': 'Request cancelled',
};

export function historyLabel(action: string): string {
  const known = Object.hasOwn(HISTORY_ACTION_LABELS, action) ? HISTORY_ACTION_LABELS[action] : undefined;
  return known ?? humanize(action);
}

export function roleLabel(role: string): string {
  return role
    .split('_')
    .map((part) => part.charAt(0) + part.slice(1).toLowerCase())
    .join(' ');
}

export function humanize(value: string): string {
  const words = value
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .split(/[._\-\s]+/)
    .filter((word) => word.length > 0);
  if (words.length === 0) return 'Unknown';
  const text = words.join(' ').toLowerCase();
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export function shortId(value: string): string {
  return value.length > 8 ? `${value.slice(0, 8)}…` : value;
}
