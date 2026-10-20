import { expect, type Page } from '@playwright/test';
import { ApiProbe, type NotificationItem } from './api';
import { currentUtcMonth } from './config';
import { check } from './facts';

export async function expectOwnRequestList(page: Page): Promise<void> {
  await expect(page.locator('#my-requests-title')).toBeVisible();
  await expect(page.locator('ul[aria-label="My purchase requests"], .state').first()).toBeVisible();
}

export function requestIdFromPath(page: Page): string {
  const match = /\/requests\/([0-9a-f-]{36})$/.exec(new URL(page.url()).pathname);
  if (match?.[1] === undefined) throw new Error('The current product path did not contain a request UUID');
  return match[1];
}

export async function openNewRequestForm(page: Page): Promise<void> {
  await page.goto('/requests/new');
  await expect(page.locator('#new-request-title')).toBeVisible();
}

export interface DraftInput {
  title: string;
  justification: string;
  amount: string;
}

export async function fillDraft(page: Page, input: DraftInput): Promise<void> {
  await page.locator('#request-title').fill(input.title);
  await page.locator('#request-justification').fill(input.justification);
  await page.locator('#request-amount').fill(input.amount);
}

export async function createDraft(page: Page, input: DraftInput): Promise<string> {
  await fillDraft(page, input);
  await page.getByRole('button', { name: 'Create draft' }).click();
  await expect(page.locator('#request-detail-title')).toHaveText(input.title, { timeout: 30_000 });
  await expect(page.locator('.submeta .badge')).toHaveText('Draft');
  return requestIdFromPath(page);
}

export async function editDraft(page: Page, input: DraftInput): Promise<void> {
  await page.getByRole('button', { name: 'Edit draft' }).click();
  await page.locator('#edit-title').fill(input.title);
  await page.locator('#edit-justification').fill(input.justification);
  await page.locator('#edit-amount').fill(input.amount);
  await page.getByRole('button', { name: 'Save draft' }).click();
  await expect(page.locator('#request-detail-title')).toHaveText(input.title, { timeout: 30_000 });
  await expect(page.getByRole('button', { name: 'Edit draft' })).toBeVisible();
}

export async function submitDraft(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Submit for approval' }).click();
  await expect(page.locator('.submeta .badge')).toHaveText('Awaiting department approval', { timeout: 30_000 });
  await expect(page.getByRole('button', { name: 'Submit for approval' })).toHaveCount(0);
}

export async function openApprovalRow(page: Page, title: string): Promise<void> {
  await expect(page.locator('#approvals-title')).toBeVisible();
  const row = page.locator('li[data-request-id]').filter({ hasText: title });
  await expect(row).toHaveCount(1, { timeout: 30_000 });
  await row.getByRole('button', { name: 'Review' }).click();
  await expect(row.locator('.decision')).toBeVisible({ timeout: 30_000 });
}

export async function approveDepartmentStep(page: Page, title: string): Promise<void> {
  await openApprovalRow(page, title);
  await page.getByRole('button', { name: 'Approve department step' }).click();
  await expect(page.locator('li[data-request-id]').filter({ hasText: title })).toHaveCount(0, {
    timeout: 30_000,
  });
}

export async function approveFinanceStep(page: Page, title: string): Promise<void> {
  await openApprovalRow(page, title);
  await page.getByRole('button', { name: 'Approve finance step' }).click();
  await expect(page.locator('li[data-request-id]').filter({ hasText: title })).toHaveCount(0, {
    timeout: 30_000,
  });
}

/** Refreshes the mounted in-app panel until the real consumer-delivered row appears. */
export async function waitForNotification(
  page: Page,
  probe: ApiProbe,
  requestId: string,
  eventType: string,
  timeoutMs = 60_000,
): Promise<NotificationItem> {
  await page.goto('/notifications');
  await expect(page.locator('#notifications-title')).toBeVisible();
  const deadline = Date.now() + timeoutMs;
  let lastStatus = 0;
  while (Date.now() < deadline) {
    const refresh = page.locator('section.notifications').getByRole('button', { name: 'Refresh' });
    if (await refresh.isVisible()) {
      const [response] = await Promise.all([
        page.waitForResponse((candidate) => candidate.url().includes('/api/notifications?')
          && candidate.request().method() === 'GET'),
        refresh.click(),
      ]);
      lastStatus = response.status();
      expect(lastStatus, 'notification list refresh must succeed').toBe(200);
      const list = await probe.notifications();
      const item = list.items.find((candidate) => candidate.requestId === requestId
        && candidate.eventType === eventType);
      if (item !== undefined) {
        // A refresh response can race the mounted list's render; keep polling the real UI
        // until the row with this consumer-delivered ID is actually visible.
        const rendered = page.locator(`li[data-notification-id="${item.id}"]`);
        if (await rendered.isVisible().catch(() => false)) return item;
      }
    }
    await page.waitForTimeout(500);
  }
  throw new Error(`No consumer-delivered ${eventType} notification appeared within the bounded wait`);
}

export async function markNotificationRead(page: Page, item: NotificationItem): Promise<void> {
  const rendered = page.locator(`li[data-notification-id="${item.id}"]`);
  await expect(rendered.locator('.badge')).toHaveText('Unread');
  await rendered.getByRole('button', { name: /Mark as read/ }).click();
  await expect(rendered.locator('.badge')).toHaveText('Read', { timeout: 20_000 });
}

export async function expectBudget(
  probe: ApiProbe,
  departmentId: string,
  expected: { limitCents: number; usedCents: number; remainingCents: number },
): Promise<void> {
  const rows = await probe.budgets(currentUtcMonth());
  const row = rows.find((candidate) => candidate.departmentId === departmentId);
  expect(row, 'the scoped department budget must exist').toBeDefined();
  if (row === undefined) return;
  expect(Math.round(row.limitAmount * 100), 'budget limit in exact cents').toBe(expected.limitCents);
  expect(Math.round(row.usedAmount * 100), 'budget usage in exact cents').toBe(expected.usedCents);
  expect(Math.round(row.remainingAmount * 100), 'server-calculated remaining cents').toBe(expected.remainingCents);
  check('budget', {
    month: row.month,
    limitCents: expected.limitCents,
    usedCents: expected.usedCents,
    remainingCents: expected.remainingCents,
  });
}
