import { expect, test } from '@playwright/test';
import type { BrowserContext } from '@playwright/test';
import { BASE_URL, cents, currentUtcMonth, jwtFacts, label } from '../helpers/config';
import { criticalTitle } from '../helpers/contract';
import { check, facts, flushFacts } from '../helpers/facts';
import {
  approveDepartmentStep,
  editDraft,
  expectBudget,
  fillDraft,
  markNotificationRead,
  openApprovalRow,
  openNewRequestForm,
  requestIdFromPath,
  submitDraft,
  waitForNotification,
} from '../helpers/product';
import {
  assertNoHorizontalOverflow,
  AuthRequestCapture,
  BrowserSessions,
  expectVisibleFocus,
  locationFacts,
  loginAt,
  recordIdentity,
  signIn,
  waitForCleanApp,
  waitForCleanPath,
} from '../helpers/session';

/**
 * One serial critical journey. Each test starts its own real browser context and performs
 * real Keycloak Code+S256 sign-ins; no shared storage state or seeded delivery rows exist.
 * The stack is disposable and owned by scripts/check-browser.py.
 */
test.describe.configure({ mode: 'serial' });

const MONTH = currentUtcMonth();
const PRIMARY_TITLE = label('primary request');
const SECOND_TITLE = label('budget denial request');
const REJECT_TITLE = label('rejected request');
const REVOKE_TITLE = label('revocation pending request');
const FOREIGN_TITLE = label('foreign company request');
const FIRST_AMOUNT_CENTS = 120_050; // EUR 1,200.50
const SECOND_AMOUNT_CENTS = 400_010; // EUR 4,000.10
const SEED_LIMIT_CENTS = 500_000;
const RAISED_LIMIT_CENTS = 600_000;

let primaryId = '';
let primaryTitle = PRIMARY_TITLE;
let secondId = '';
let rejectId = '';
let revokeId = '';
let foreignId = '';
let employeeDepartmentId = '';
let primaryCorrelation = '';

/**
 * Parsed CSP directive map (directive name -> lower-cased tokens). A substring check alone
 * would accept a later 'unsafe-inline'/'unsafe-eval' addition to script-src.
 */
function cspDirectives(header: string): Record<string, string[]> {
  const directives: Record<string, string[]> = {};
  for (const part of header.split(';')) {
    const tokens = part.trim().split(/\s+/).filter((token) => token.length > 0);
    const name = tokens[0];
    if (name === undefined) continue;
    directives[name.toLowerCase()] = tokens.slice(1).map((token) => token.toLowerCase());
  }
  return directives;
}

const WEAK_SCRIPT_TOKENS = new Set(["'unsafe-inline'", "'unsafe-eval'", 'data:', 'blob:', '*']);

/**
 * Real response-header assertions for the SPA document. The header is the enforced policy;
 * angular.json keeps the built index free of inline script/style so script-src can stay 'self'.
 */
async function assertBrowserSecurityHeaders(context: BrowserContext): Promise<{
  allPresent: boolean;
  rootReachable: boolean;
  headerFacts: Record<string, boolean>;
}> {
  const response = await context.request.get(`${BASE_URL}/`, { headers: { Accept: 'text/html' } });
  expect(response.status(), 'the SPA root must be served over the real frontend').toBe(200);
  const headers = response.headers();
  const csp = headers['content-security-policy'] ?? '';
  const body = await response.text();
  const directives = cspDirectives(csp);
  const scriptSrc = directives['script-src'] ?? directives['default-src'] ?? [];
  const headerFacts = {
    scriptSrcSelf: csp.includes("script-src 'self'"),
    scriptSrcSelfOnly: scriptSrc.length === 1 && scriptSrc[0] === "'self'",
    scriptSrcNoWeakTokens: scriptSrc.every((token) => !WEAK_SCRIPT_TOKENS.has(token)),
    styleSrcUnsafeInline: csp.includes("style-src 'self' 'unsafe-inline'"),
    connectSrcSelf: csp.includes("connect-src 'self'"),
    objectSrcNone: csp.includes("object-src 'none'"),
    baseUriSelf: csp.includes("base-uri 'self'"),
    formActionSelf: csp.includes("form-action 'self'"),
    frameAncestorsNone: csp.includes("frame-ancestors 'none'"),
    xFrameOptionsDeny: headers['x-frame-options'] === 'DENY',
    coopSameOrigin: headers['cross-origin-opener-policy'] === 'same-origin',
    nosniff: headers['x-content-type-options'] === 'nosniff',
    referrerPolicy: headers['referrer-policy'] === 'strict-origin-when-cross-origin',
    noInlineScript: !/<script(?![^>]*\bsrc=)[^>]*>/i.test(body),
    noInlineStyle: !/<style/i.test(body),
  };
  const missing = Object.entries(headerFacts).filter(([, present]) => !present).map(([name]) => name);
  expect(missing, 'the SPA response must carry every required browser-security header').toEqual([]);
  return { allPresent: missing.length === 0, rootReachable: response.status() === 200, headerFacts };
}

/**
 * A foreign document must not be able to frame the product. `frame-ancestors 'none'` is
 * enforced by the browser on navigation of the nested browsing context, so a rejected
 * navigation and a frame that stays on its initial empty document are the observables.
 * The rejection is a CSP decision, not an unreachable origin: the same browser context's
 * network stack fetched the root document with 200 immediately before this probe.
 */
async function assertForeignFrameBlocked(
  context: BrowserContext,
  originReachable: boolean,
): Promise<{
  blocked: boolean;
  navigationRejected: boolean;
  stayedEmpty: boolean;
}> {
  expect(originReachable, 'the same-origin root fetch must succeed before the frame probe').toBe(true);
  const attacker = await context.newPage();
  try {
    await attacker.setContent(
      '<!doctype html><html><body><iframe id="victim" src="about:blank"></iframe></body></html>',
    );
    const victim = attacker.frames()[1] ?? await attacker.waitForEvent('frameattached');
    let navigationRejected = false;
    try {
      await victim.goto(`${BASE_URL}/`, { timeout: 15_000 });
    } catch {
      navigationRejected = true;
    }
    const frameUrl = victim.url();
    const loaded = frameUrl.startsWith(BASE_URL);
    const stayedEmpty = frameUrl === 'about:blank' || frameUrl === '';
    expect(navigationRejected, 'the blocked frame navigation must reject instead of committing').toBe(true);
    expect(loaded, 'frame-ancestors none must block a foreign iframe from loading the SPA').toBe(false);
    expect(stayedEmpty, 'the blocked frame must stay on its initial empty document').toBe(true);
    return { blocked: !loaded, navigationRejected, stayedEmpty };
  } finally {
    await attacker.close();
  }
}

test.beforeAll(async ({ browser }) => {
  facts.run.browserVersion = browser.version();
  facts.run.month = MONTH;
  flushFacts();
});

test(criticalTitle('employee-lifecycle'), async ({ browser }) => {
  const sessions = new BrowserSessions(browser);
  try {
    const employee = await sessions.open();
    const appOrigin = new URL(BASE_URL).origin;
    const cspViolations: string[] = [];
    employee.page.on('console', (message) => {
      if (message.type() !== 'error') return;
      const text = message.text();
      if (!text.includes('Content Security Policy')) return;
      // The Keycloak login document shares this Page object; only the product origin counts.
      const source = message.location()?.url ?? '';
      if (source && !source.startsWith(appOrigin)) return;
      cspViolations.push(text.replace(/https?:\/\/\S+/g, '[URL]').slice(0, 240));
    });
    const security = await assertBrowserSecurityHeaders(employee.context);
    const frame = await assertForeignFrameBlocked(employee.context, security.rootReachable);
    await loginAt(employee.page, 'employee');
    const me = await employee.probe.me();
    employeeDepartmentId = me.departmentId;
    recordIdentity('employee', employee.probe, me);
    expect(me.roles).toContain('EMPLOYEE');

    await openNewRequestForm(employee.page);
    await employee.page.setViewportSize({ width: 375, height: 812 });
    check('employeeFormNarrow', await assertNoHorizontalOverflow(employee.page, 375));

    await employee.page.locator('#request-title').focus();
    await employee.page.keyboard.press('Tab');
    const focus = await expectVisibleFocus(employee.page.locator('#request-justification'));
    await employee.page.keyboard.press('Tab');
    await expectVisibleFocus(employee.page.locator('#request-amount'));
    await employee.page.keyboard.press('Tab');
    await expectVisibleFocus(employee.page.getByRole('button', { name: 'Create draft' }));
    check('keyboardFocus', focus);

    let createPosts = 0;
    employee.page.on('request', (request) => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/requests') createPosts += 1;
    });

    await fillDraft(employee.page, {
      title: `${PRIMARY_TITLE} invalid`,
      justification: 'Invalid precision must never reach the write endpoint.',
      amount: '1.005',
    });
    await employee.page.getByRole('button', { name: 'Create draft' }).click();
    await expect(employee.page.locator('#request-amount-error')).toBeVisible();
    expect(createPosts, 'invalid precision must not issue a create request').toBe(0);
    expect(new URL(employee.page.url()).pathname).toBe('/requests/new');

    await fillDraft(employee.page, {
      title: PRIMARY_TITLE,
      justification: 'Critical journey draft; fictional office equipment, no real purchase.',
      amount: '1200.50',
    });
    await employee.page.getByRole('button', { name: 'Create draft' }).click();
    await expect(employee.page.locator('#request-detail-title')).toHaveText(PRIMARY_TITLE, { timeout: 30_000 });
    await expect.poll(() => createPosts, { timeout: 15_000 }).toBe(1);
    primaryId = requestIdFromPath(employee.page);
    let canonical = await employee.probe.request(primaryId);
    primaryCorrelation = canonical.correlationId;
    expect(canonical.status).toBe('DRAFT');
    expect(cents(canonical.amount)).toBe(FIRST_AMOUNT_CENTS);
    expect(canonical.version).toBe(0);

    primaryTitle = `${PRIMARY_TITLE} revised`;
    await editDraft(employee.page, {
      title: primaryTitle,
      justification: 'Critical journey draft revised before the explicit submit action.',
      amount: '1200.50',
    });
    canonical = await employee.probe.request(primaryId);
    expect(canonical.status).toBe('DRAFT');
    expect(canonical.version).toBe(1);

    await submitDraft(employee.page);
    canonical = await employee.probe.request(primaryId);
    expect(canonical.status).toBe('AWAITING_DEPARTMENT_APPROVAL');
    expect(canonical.version).toBe(2);
    expect(canonical.submittedAt).not.toBeNull();

    const selfApproval = await employee.probe.approve(primaryId, canonical.version);
    expect(selfApproval.status, 'requester self-approval must be denied by the server').toBe(403);
    expect((await employee.probe.request(primaryId)).status).toBe('AWAITING_DEPARTMENT_APPROVAL');

    const history = await employee.probe.history(primaryId);
    check('employeeLifecycle', {
      requestId: primaryId,
      correlationId: primaryCorrelation,
      actions: history.map((entry) => entry.action),
      selfApprovalStatus: selfApproval.status,
      invalidPrecisionWrote: createPosts > 1,
      securityHeadersPresent: security.allPresent,
      securityHeaderFacts: security.headerFacts,
      foreignFrameBlocked: frame.blocked,
      foreignFrameNavigationRejected: frame.navigationRejected,
      foreignFrameStayedEmpty: frame.stayedEmpty,
      cspViolations: cspViolations.length,
    });
    expect(cspViolations, 'the real SPA session must not log any CSP violation').toEqual([]);
    expect(history.map((entry) => entry.action)).toEqual([
      'request.created',
      'request.edited',
      'request.submitted',
    ]);
    facts.requests['primary'] = {
      id: primaryId,
      correlationId: primaryCorrelation,
      title: primaryTitle,
      amountCents: FIRST_AMOUNT_CENTS,
      status: 'AWAITING_DEPARTMENT_APPROVAL',
    };
    flushFacts();
  } finally {
    await sessions.closeAll();
  }
});

test(criticalTitle('dual-approval'), async ({ browser }) => {
  const sessions = new BrowserSessions(browser);
  try {
    const manager = await sessions.login('manager', { width: 375, height: 812 });
    const managerMe = await manager.probe.me();
    recordIdentity('manager', manager.probe, managerMe);
    expect(managerMe.roles).toContain('DEPARTMENT_MANAGER');

    await manager.page.goto('/approvals');
    await expect(manager.page.locator('#approvals-title')).toBeVisible();
    await expect(manager.page.locator('li[data-request-id]').filter({ hasText: primaryTitle })).toHaveCount(1);
    check('managerInboxNarrow', await assertNoHorizontalOverflow(manager.page, 375));
    await manager.page.locator('#approval-status-filter').focus();
    await manager.page.keyboard.press('Tab');
    const inboxFocus = await expectVisibleFocus(manager.page.getByRole('button', { name: 'Refresh' }));
    await manager.page.keyboard.press('Tab');
    await expectVisibleFocus(
      manager.page.locator('li[data-request-id]').filter({ hasText: primaryTitle })
        .getByRole('button', { name: 'Review' }),
    );
    check('inboxKeyboardFocus', inboxFocus);
    await manager.page.setViewportSize({ width: 1280, height: 900 });

    await openApprovalRow(manager.page, primaryTitle);
    await expect(manager.page.locator('.decision .step')).toHaveText('department step');
    await expect(manager.page.locator('li[data-request-id]').filter({ hasText: primaryTitle }))
      .toContainText('€1,200.50');
    await manager.page.getByRole('button', { name: 'Approve department step' }).click();
    await expect(manager.page.locator('li[data-request-id]').filter({ hasText: primaryTitle }))
      .toHaveCount(0, { timeout: 30_000 });

    let canonical = await manager.probe.request(primaryId);
    expect(canonical.status).toBe('AWAITING_FINANCE_APPROVAL');
    expect(canonical.departmentApproverIdentityId).toBe(managerMe.identityId);

    const employee = await sessions.login('employee');
    const employeeMe = await employee.probe.me();
    await employee.page.goto(`/requests/${primaryId}`);
    await expect(employee.page.locator('#request-detail-title')).toHaveText(primaryTitle);
    await expect(employee.page.locator('.submeta .badge')).toHaveText('Awaiting finance approval');

    const employeeNotification = await waitForNotification(
      employee.page,
      employee.probe,
      primaryId,
      'request.department_approved',
    );
    check('employeeNotification', {
      eventId: employeeNotification.eventId,
      eventType: employeeNotification.eventType,
      correlationId: employeeNotification.correlationId,
    });
    const beforeRead = await employee.probe.notifications();
    const beforeReadRow = beforeRead.items.find((item) => item.id === employeeNotification.id);
    expect(beforeReadRow?.readAt, 'consumer-created notification starts unread').toBeNull();
    await markNotificationRead(employee.page, employeeNotification);
    const afterRead = (await employee.probe.notifications()).items.find((item) => item.id === employeeNotification.id);
    expect(afterRead?.readAt).not.toBeNull();
    const firstReadAt = afterRead?.readAt;

    await employee.page.reload();
    const readRow = employee.page.locator(`li[data-notification-id="${employeeNotification.id}"]`);
    await expect(readRow.locator('.badge')).toHaveText('Read');
    const reloaded = (await employee.probe.notifications()).items.find((item) => item.id === employeeNotification.id);
    expect(reloaded?.readAt).toBe(firstReadAt);
    facts.notifications['employee'] = {
      eventId: employeeNotification.eventId,
      correlationId: employeeNotification.correlationId,
      readPersisted: reloaded?.readAt === firstReadAt,
    };

    const finance = await sessions.login('finance');
    const financeMe = await finance.probe.me();
    recordIdentity('finance', finance.probe, financeMe);
    expect(financeMe.roles).toContain('FINANCE_APPROVER');
    expect(financeMe.identityId).not.toBe(managerMe.identityId);
    expect(financeMe.identityId).not.toBe(employeeMe.identityId);

    await finance.page.goto('/approvals');
    await openApprovalRow(finance.page, primaryTitle);
    await expect(finance.page.locator('.decision .step')).toHaveText('finance step');
    await finance.page.getByRole('button', { name: 'Approve finance step' }).click();
    await expect(finance.page.locator('li[data-request-id]').filter({ hasText: primaryTitle }))
      .toHaveCount(0, { timeout: 30_000 });

    canonical = await employee.probe.request(primaryId);
    expect(canonical.status).toBe('APPROVED');
    expect(canonical.budgetMonth).toBe(MONTH);
    expect(canonical.approvedAt).not.toBeNull();

    await employee.page.goto(`/requests/${primaryId}`);
    await expect(employee.page.locator('.submeta .badge')).toHaveText('Approved');
    await expect(employee.page.locator('.route-note')).toBeVisible();
    const timeline = employee.page.locator('.history li');
    await expect(timeline).toHaveCount(5);
    await expect(employee.page.locator('.history')).toContainText('Department approved');
    await expect(employee.page.locator('.history')).toContainText('Final approval recorded');

    const history = await employee.probe.history(primaryId);
    expect(history.map((entry) => `${entry.action}:${entry.toStatus}`)).toEqual([
      'request.created:DRAFT',
      'request.edited:DRAFT',
      'request.submitted:AWAITING_DEPARTMENT_APPROVAL',
      'request.department_approved:AWAITING_FINANCE_APPROVAL',
      'request.approved:APPROVED',
    ]);
    expect(history[3]?.actorIdentityId).toBe(managerMe.identityId);
    expect(history[4]?.actorIdentityId).toBe(financeMe.identityId);
    check('approvalTimeline', {
      actions: history.map((entry) => entry.action),
      managerActor: history[3]?.actorIdentityId,
      financeActor: history[4]?.actorIdentityId,
    });

    await expectBudget(finance.probe, employeeDepartmentId, {
      limitCents: SEED_LIMIT_CENTS,
      usedCents: FIRST_AMOUNT_CENTS,
      remainingCents: SEED_LIMIT_CENTS - FIRST_AMOUNT_CENTS,
    });

    const managerNotification = await waitForNotification(
      manager.page,
      manager.probe,
      primaryId,
      'request.approved',
    );
    check('managerNotification', {
      eventId: managerNotification.eventId,
      eventType: managerNotification.eventType,
    });
    facts.requests['primary'] = {
      ...(facts.requests['primary'] ?? {}),
      status: 'APPROVED',
      budgetMonth: canonical.budgetMonth,
      version: canonical.version,
    };
    flushFacts();
  } finally {
    await sessions.closeAll();
  }
});

test(criticalTitle('budget-denial-retry'), async ({ browser }) => {
  const sessions = new BrowserSessions(browser);
  try {
    const employee = await sessions.login('employee');
    await openNewRequestForm(employee.page);
    await fillDraft(employee.page, {
      title: SECOND_TITLE,
      justification: 'Budget denial fixture; above the remaining fictional monthly limit.',
      amount: '4000.10',
    });
    await employee.page.getByRole('button', { name: 'Create draft' }).click();
    await expect(employee.page.locator('#request-detail-title')).toHaveText(SECOND_TITLE, { timeout: 30_000 });
    secondId = requestIdFromPath(employee.page);
    await submitDraft(employee.page);

    const manager = await sessions.login('manager');
    await manager.page.goto('/approvals');
    await approveDepartmentStep(manager.page, SECOND_TITLE);

    const finance = await sessions.login('finance');
    await finance.page.goto('/approvals');
    await openApprovalRow(finance.page, SECOND_TITLE);
    await finance.page.getByRole('button', { name: 'Approve finance step' }).click();
    await expect(finance.page.locator('.decision .error')).toContainText(
      'Final approval was not saved because the budget for the approval month is insufficient',
    );
    const denied = await finance.probe.request(secondId);
    expect(denied.status).toBe('AWAITING_FINANCE_APPROVAL');
    await expectBudget(finance.probe, employeeDepartmentId, {
      limitCents: SEED_LIMIT_CENTS,
      usedCents: FIRST_AMOUNT_CENTS,
      remainingCents: SEED_LIMIT_CENTS - FIRST_AMOUNT_CENTS,
    });
    check('insufficientBudgetDenial', { status: denied.status, requestVersion: denied.version });

    const admin = await sessions.login('admin');
    const adminMe = await admin.probe.me();
    recordIdentity('admin', admin.probe, adminMe);
    await admin.page.goto('/budgets');
    await expect(admin.page.locator('#budget-month')).toHaveValue(MONTH);
    await expect(admin.page.locator('#limit-department')).toBeVisible({ timeout: 30_000 });
    await admin.page.locator('#limit-department').selectOption({ label: 'engineering' });
    await admin.page.locator('#limit-amount').fill('6000.00');
    await admin.page.getByRole('button', { name: 'Save limit' }).click();
    await expect(admin.page.locator('.recorded')).toContainText('€6,000.00', { timeout: 30_000 });
    await expect(admin.page.locator('.recorded')).toContainText('€1,200.50');
    await expectBudget(admin.probe, employeeDepartmentId, {
      limitCents: RAISED_LIMIT_CENTS,
      usedCents: FIRST_AMOUNT_CENTS,
      remainingCents: RAISED_LIMIT_CENTS - FIRST_AMOUNT_CENTS,
    });

    await finance.page.getByRole('button', { name: 'Reload current request' }).click();
    await expect(finance.page.locator('.decision')).toBeVisible({ timeout: 30_000 });
    await finance.page.getByRole('button', { name: 'Approve finance step' }).click();
    await expect(finance.page.locator('li[data-request-id]').filter({ hasText: SECOND_TITLE }))
      .toHaveCount(0, { timeout: 30_000 });

    const approved = await employee.probe.request(secondId);
    expect(approved.status).toBe('APPROVED');
    await expectBudget(finance.probe, employeeDepartmentId, {
      limitCents: RAISED_LIMIT_CENTS,
      usedCents: FIRST_AMOUNT_CENTS + SECOND_AMOUNT_CENTS,
      remainingCents: RAISED_LIMIT_CENTS - FIRST_AMOUNT_CENTS - SECOND_AMOUNT_CENTS,
    });
    check('budgetRaiseAndRetry', {
      requestId: secondId,
      status: approved.status,
      limitCents: RAISED_LIMIT_CENTS,
      usedCents: FIRST_AMOUNT_CENTS + SECOND_AMOUNT_CENTS,
    });
    facts.requests['second'] = {
      id: secondId,
      title: SECOND_TITLE,
      amountCents: SECOND_AMOUNT_CENTS,
      status: approved.status,
      budgetMonth: approved.budgetMonth,
    };
    facts.budgets['afterRaise'] = {
      limitCents: RAISED_LIMIT_CENTS,
      usedCents: FIRST_AMOUNT_CENTS + SECOND_AMOUNT_CENTS,
      remainingCents: RAISED_LIMIT_CENTS - FIRST_AMOUNT_CENTS - SECOND_AMOUNT_CENTS,
    };
    flushFacts();
  } finally {
    await sessions.closeAll();
  }
});

test(criticalTitle('terminal-rejection'), async ({ browser }) => {
  const sessions = new BrowserSessions(browser);
  try {
    const employee = await sessions.login('employee');
    await openNewRequestForm(employee.page);
    await fillDraft(employee.page, {
      title: REJECT_TITLE,
      justification: 'Rejection fixture with a fictional reason.',
      amount: '300.00',
    });
    await employee.page.getByRole('button', { name: 'Create draft' }).click();
    await expect(employee.page.locator('#request-detail-title')).toHaveText(REJECT_TITLE, { timeout: 30_000 });
    rejectId = requestIdFromPath(employee.page);
    await submitDraft(employee.page);

    const manager = await sessions.login('manager');
    await manager.page.goto('/approvals');
    await openApprovalRow(manager.page, REJECT_TITLE);
    await manager.page.getByRole('button', { name: 'Reject request' }).click();
    const reason = 'Fictional duplicate purchase; no business need.';
    await manager.page.locator('textarea[id^="reject-reason-"]').fill(reason);
    await manager.page.getByRole('button', { name: 'Confirm rejection' }).click();
    await expect(manager.page.locator('li[data-request-id]').filter({ hasText: REJECT_TITLE }))
      .toHaveCount(0, { timeout: 30_000 });

    const rejected = await employee.probe.request(rejectId);
    expect(rejected.status).toBe('REJECTED');
    const history = await employee.probe.history(rejectId);
    expect(history.at(-1)?.action).toBe('request.rejected');
    expect(history.at(-1)?.reason).toBe(reason);

    await employee.page.goto(`/requests/${rejectId}`);
    await expect(employee.page.locator('.submeta .badge')).toHaveText('Rejected');
    await expect(employee.page.getByText('This request is in a terminal state. No further approval or cancellation action is available.'))
      .toBeVisible();
    await expect(employee.page.getByRole('button', { name: 'Cancel request' })).toHaveCount(0);
    await expect(employee.page.locator('.decision')).toHaveCount(0);

    const notification = await waitForNotification(employee.page, employee.probe, rejectId, 'request.rejected');
    check('rejection', {
      requestId: rejectId,
      reason,
      status: rejected.status,
      eventId: notification.eventId,
    });
    facts.requests['rejected'] = { id: rejectId, status: rejected.status, reason };
    flushFacts();
  } finally {
    await sessions.closeAll();
  }
});

test(criticalTitle('deep-link-callback'), async ({ browser }) => {
  const sessions = new BrowserSessions(browser);
  try {
    const employee = await sessions.login('employee');
    const deepPath = `/requests/${primaryId}`;

    const warm = await employee.context.newPage();
    await warm.goto(`${BASE_URL}${deepPath}`);
    await expect(warm.locator('#request-detail-title')).toHaveText(primaryTitle, { timeout: 45_000 });
    const warmReady = await waitForCleanPath(warm, deepPath);
    await warm.reload();
    await expect(warm.locator('#request-detail-title')).toHaveText(primaryTitle, { timeout: 45_000 });
    const warmAfterRefresh = await waitForCleanPath(warm, deepPath);
    check('warmDeepLink', {
      queryNames: warmReady.queryNames,
      hashNames: warmReady.hashNames,
      refreshedClean: warmAfterRefresh.clean,
    });
    await warm.close();

    const cold = await sessions.open();
    const capture = new AuthRequestCapture(cold.page);
    await cold.page.goto(`${BASE_URL}${deepPath}`);
    await expect(cold.page.locator('app-session-panel').getByRole('button', { name: 'Sign in' }))
      .toBeVisible({ timeout: 45_000 });
    await signIn(cold.page, 'employee');
    const afterReady = locationFacts(cold.page);
    await waitForCleanPath(cold.page, deepPath);
    await expect(cold.page.locator('#request-detail-title')).toHaveText(primaryTitle, { timeout: 45_000 });
    expect(capture.facts, 'the browser authorization request must be observed').not.toBeNull();
    expect(capture.facts?.redirectUriExactRoot).toBe(true);
    expect(capture.facts?.responseTypeCode).toBe(true);
    expect(capture.facts?.codeChallengeMethodS256).toBe(true);
    expect(capture.facts?.codeChallengePresent).toBe(true);
    expect(capture.facts?.noncePresent).toBe(true);

    await cold.page.goBack();
    await cold.page.waitForURL((url) => url.origin === new URL(BASE_URL).origin, { timeout: 45_000 });
    const afterBack = await waitForCleanApp(cold.page);
    check('coldDeepLink', {
      authRequest: capture.facts,
      afterReady: {
        queryNames: afterReady.queryNames,
        hashNames: afterReady.hashNames,
        clean: afterReady.clean,
      },
      afterBack: {
        pathname: afterBack.pathname,
        queryNames: afterBack.queryNames,
        hashNames: afterBack.hashNames,
        clean: afterBack.clean,
      },
      detailRestored: true,
    });
    facts.callback = {
      redirectUriExactRoot: capture.facts?.redirectUriExactRoot ?? false,
      responseTypeCode: capture.facts?.responseTypeCode ?? false,
      codeChallengeMethodS256: capture.facts?.codeChallengeMethodS256 ?? false,
      callbackNamesAfterReady: [...afterReady.queryNames, ...afterReady.hashNames],
      callbackNamesAfterBack: [...afterBack.queryNames, ...afterBack.hashNames],
      warmRefreshedClean: warmAfterRefresh.clean,
    };
    flushFacts();
  } finally {
    await sessions.closeAll();
  }
});

test(criticalTitle('foreign-company-uuid'), async ({ browser }) => {
  const sessions = new BrowserSessions(browser);
  try {
    const foreign = await sessions.login('foreignMember');
    const foreignMe = await foreign.probe.me();
    recordIdentity('foreignMember', foreign.probe, foreignMe);
    await openNewRequestForm(foreign.page);
    await fillDraft(foreign.page, {
      title: FOREIGN_TITLE,
      justification: 'Foreign-company isolation fixture; no content should cross the boundary.',
      amount: '25.00',
    });
    await foreign.page.getByRole('button', { name: 'Create draft' }).click();
    await expect(foreign.page.locator('#request-detail-title')).toHaveText(FOREIGN_TITLE, { timeout: 30_000 });
    foreignId = requestIdFromPath(foreign.page);
    expect(foreignMe.companyName).not.toBe('Larkvane');

    const employee = await sessions.login('employee');
    const employeeMe = await employee.probe.me();
    expect(employeeMe.companyId).not.toBe(foreignMe.companyId);
    const direct = await employee.probe.get<unknown>(`/api/requests/${foreignId}`);
    expect(direct.status).toBe(404);
    expect(JSON.stringify(direct.body ?? '')).not.toContain(FOREIGN_TITLE);

    await employee.page.goto(`/requests/${foreignId}`);
    await expect(employee.page.locator('#request-detail-title')).toHaveText('Request not found', { timeout: 30_000 });
    await expect(employee.page.getByText(FOREIGN_TITLE, { exact: true })).toHaveCount(0);
    await expect(employee.page.getByText('No request content is shown for an address that is outside your current company.'))
      .toBeVisible();
    check('foreignDirectUuid', {
      status: direct.status,
      foreignRequestIdPresent: foreignId.length === 36,
      contentDisclosed: false,
      requesterCompany: employeeMe.companyId,
      foreignCompany: foreignMe.companyId,
    });
    facts.requests['foreign'] = { id: foreignId, status: 'DRAFT' };
    flushFacts();
  } finally {
    await sessions.closeAll();
  }
});

test(criticalTitle('mounted-revocation'), async ({ browser }) => {
  const sessions = new BrowserSessions(browser);
  try {
    const employee = await sessions.login('employee');
    await openNewRequestForm(employee.page);
    await fillDraft(employee.page, {
      title: REVOKE_TITLE,
      justification: 'Revocation fixture; pending while the administrator deactivates the manager.',
      amount: '250.00',
    });
    await employee.page.getByRole('button', { name: 'Create draft' }).click();
    await expect(employee.page.locator('#request-detail-title')).toHaveText(REVOKE_TITLE, { timeout: 30_000 });
    revokeId = requestIdFromPath(employee.page);
    await submitDraft(employee.page);

    const manager = await sessions.login('manager');
    const managerMe = await manager.probe.me();
    await manager.page.goto('/approvals');
    const targetRow = manager.page.locator('li[data-request-id]').filter({ hasText: REVOKE_TITLE });
    await expect(targetRow).toHaveCount(1, { timeout: 30_000 });
    const rowsBefore = await manager.page.locator('li[data-request-id]').count();
    expect(rowsBefore).toBeGreaterThan(0);
    // Record exactly the token presented by the mounted inbox just before deactivation.
    recordIdentity('managerBeforeRevocation', manager.probe, managerMe);
    const managerJwt = facts.identities['managerBeforeRevocation'];
    expect(managerJwt, 'the manager JWT facts must be captured before revocation').toBeDefined();
    expect(managerJwt?.expired, 'the old manager JWT must still be unexpired').toBe(false);
    // The exact pre-revocation token stays in memory only; the reload cannot replace this proof.
    const staleToken = manager.probe.accessValue;
    const mountedUrl = manager.page.url();
    const mountedBefore = locationFacts(manager.page);
    expect(mountedBefore.pathname, 'the inbox must stay mounted before the admin write').toBe('/approvals');
    expect(mountedBefore.clean, 'the mounted inbox must have a clean URL before the admin write').toBe(true);

    const admin = await sessions.login('admin');
    await admin.page.goto('/administration');
    await expect(admin.page.locator('#administration-title')).toBeVisible();
    const card = admin.page.locator('li.membership-card').filter({ hasText: managerMe.identityId });
    await expect(card, 'the seeded manager membership must be identifiable by identity UUID').toHaveCount(1);
    await card.getByRole('button', { name: 'Edit this membership' }).click();
    await expect(admin.page.locator('#edit-active')).toBeVisible({ timeout: 30_000 });
    await expect(admin.page.locator('#edit-active')).toBeChecked();
    await admin.page.locator('#edit-active').uncheck();
    await admin.page.getByRole('button', { name: 'Save membership' }).click();
    await expect(admin.page.locator('.write-status').last()).toContainText('Active: no.', { timeout: 30_000 });

    // No navigation, reload or cold bootstrap: the original mounted inbox and its rows are still
    // present. The next observable change must come from the explicit mounted Refresh action.
    await expect(targetRow).toHaveCount(1);
    expect(manager.page.url(), 'the mounted inbox URL must not change before the explicit refresh').toBe(mountedUrl);
    const urlUnchangedBeforeRefresh = manager.page.url() === mountedUrl;
    expect(urlUnchangedBeforeRefresh).toBe(true);

    const [refreshResponse] = await Promise.all([
      manager.page.waitForResponse((response) => {
        const url = new URL(response.url());
        return url.origin === new URL(BASE_URL).origin
          && url.pathname === '/api/me'
          && response.request().method() === 'GET';
      }, { timeout: 30_000 }),
      manager.page.getByRole('button', { name: 'Refresh' }).click(),
    ]);
    expect(refreshResponse.status(), 'the mounted refresh must be denied by the real API').toBe(403);
    const presentedHeader = refreshResponse.request().headers()['authorization'] ?? '';
    expect(presentedHeader.startsWith('Bearer '), 'the mounted refresh must use the interceptor bearer').toBe(true);
    const presentedJwt = jwtFacts(presentedHeader.slice('Bearer '.length));
    expect(presentedJwt.fingerprint, 'the mounted refresh must present the captured pre-revocation JWT')
      .toBe(managerJwt?.fingerprint);
    expect(presentedJwt.expired, 'the presented JWT must still be unexpired at the denial').toBe(false);

    await expect(manager.page.locator('.account .hint').filter({ hasText: 'active company membership' }))
      .toBeVisible({ timeout: 30_000 });
    await expect(manager.page.locator('.account .roles')).toHaveText('Current roles unavailable');
    await expect(manager.page.getByRole('link', { name: 'Approvals' })).toHaveCount(0);
    await expect(manager.page.locator('#approvals-title')).toHaveCount(0);
    await expect(manager.page.locator('li[data-request-id]')).toHaveCount(0);
    await expect(manager.page.getByText(REVOKE_TITLE, { exact: true })).toHaveCount(0);
    await expect(manager.page.locator(`li[data-request-id="${revokeId}"]`)).toHaveCount(0);
    const remainingRows = await manager.page.locator('li[data-request-id]').count();
    expect(remainingRows, 'the mounted inbox must not keep pre-revocation rows').toBe(0);

    const deniedMe = await manager.probe.getWithToken<{ code?: string }>('/api/me', staleToken);
    expect(deniedMe.status).toBe(403);
    const deniedInbox = await manager.probe.getWithToken<unknown>(
      '/api/requests?view=inbox&page=0&size=20',
      staleToken,
    );
    expect(deniedInbox.status).toBe(403);
    const oldJwtAtDenial = jwtFacts(staleToken);
    expect(oldJwtAtDenial.expired, 'the old JWT must still be unexpired at the actual denial').toBe(false);
    expect(oldJwtAtDenial.fingerprint, 'the old JWT must be byte-identical at the actual denial')
      .toBe(managerJwt?.fingerprint);

    check('revocationWithUnexpiredJwt', {
      managerIdentityId: managerMe.identityId,
      jwtExpiredAtCapture: managerJwt?.expired,
      jwtExpiresInSecondsAtCapture: managerJwt?.expiresInSeconds,
      capturedBeforeAdminWrite: true,
      urlUnchangedBeforeRefresh,
      rowsBefore,
      mountedRefreshStatus: refreshResponse.status(),
      interceptorBearerPresent: presentedHeader.startsWith('Bearer '),
      presentedJwtFingerprintUnchanged: presentedJwt.fingerprint === managerJwt?.fingerprint,
      oldJwtExpiredAtDenial: oldJwtAtDenial.expired,
      oldJwtFingerprintUnchanged: oldJwtAtDenial.fingerprint === managerJwt?.fingerprint,
      deniedMeStatus: deniedMe.status,
      deniedInboxStatus: deniedInbox.status,
      remainingRows,
      clearedRows: rowsBefore - remainingRows,
      sessionForbiddenAfterRefresh: true,
      rolesUnavailableAfterRefresh: true,
      approvalsNavigationRemoved: true,
      navigationAfterRefresh: new URL(manager.page.url()).pathname === '/welcome',
    });
    facts.requests['revocationPending'] = { id: revokeId, status: 'AWAITING_DEPARTMENT_APPROVAL' };
    flushFacts();
  } finally {
    await sessions.closeAll();
  }
});
