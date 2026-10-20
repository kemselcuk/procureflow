import type { Page } from '@playwright/test';
import { BASE_URL } from './config';

export interface Me {
  identityId: string;
  membershipId: string;
  companyId: string;
  companyName: string;
  departmentId: string;
  departmentName: string;
  version: number;
  roles: string[];
  managedDepartmentIds: string[];
}

export interface BudgetView {
  id: string;
  departmentId: string;
  month: string;
  limitAmount: number;
  usedAmount: number;
  remainingAmount: number;
  version: number;
}

export interface HistoryEntry {
  id: string;
  version: number;
  action: string;
  fromStatus: string | null;
  toStatus: string;
  actorIdentityId: string;
  reason: string | null;
  correlationId: string;
  occurredAt: string;
}

export interface NotificationItem {
  id: string;
  eventId: string;
  eventType: string;
  requestId: string;
  correlationId: string;
  occurredAt: string;
  createdAt: string;
  readAt: string | null;
}

export interface NotificationPage {
  page: number;
  size: number;
  total: number;
  items: NotificationItem[];
}

export interface RequestView {
  id: string;
  companyId: string;
  requesterIdentityId: string;
  departmentId: string;
  title: string;
  justification: string;
  amount: number;
  currency: string;
  status: string;
  version: number;
  createdAt: string;
  updatedAt: string;
  submittedAt: string | null;
  departmentApproverIdentityId: string | null;
  correlationId: string;
  approvedAt: string | null;
  budgetMonth: string | null;
}

interface Result<T> {
  status: number;
  body: T | null;
}

/**
 * Reads the bearer value from the real product request made by this page and keeps it only
 * in memory for crosschecks. Tokens are never printed, persisted, attached to failures or
 * written to the facts file; facts expose only a hash prefix and claim timing.
 */
export class ApiProbe {
  private token: string | null = null;
  private readonly page: Page;

  constructor(page: Page) {
    this.page = page;
    page.on('request', (request) => {
      const value = request.headers()['authorization'];
      if (value !== undefined && value.startsWith('Bearer ')) {
        this.token = value.slice('Bearer '.length);
      }
    });
  }

  async waitForToken(): Promise<void> {
    const deadline = Date.now() + 20_000;
    while (this.token === null && Date.now() < deadline) {
      await this.page.waitForTimeout(50);
    }
    if (this.token === null) throw new Error('No authenticated product API request was observed');
  }

  get accessValue(): string {
    if (this.token === null) throw new Error('No bearer value is available yet');
    return this.token;
  }

  async get<T>(path: string): Promise<Result<T>> {
    await this.waitForToken();
    return this.getWithToken<T>(path, this.accessValue);
  }

  /** Crosscheck with a token captured earlier, without changing the probe's current value. */
  async getWithToken<T>(path: string, token: string): Promise<Result<T>> {
    const response = await this.page.request.get(new URL(path, BASE_URL).toString(), {
      headers: { Authorization: `Bearer ${token}` },
      timeout: 20_000,
    });
    return this.parse<T>(response.status(), path, response);
  }

  async post<T>(path: string, body: unknown): Promise<Result<T>> {
    await this.waitForToken();
    const response = await this.page.request.post(new URL(path, BASE_URL).toString(), {
      headers: {
        Authorization: `Bearer ${this.accessValue}`,
        'Content-Type': 'application/json',
      },
      data: body,
      timeout: 20_000,
    });
    return this.parse<T>(response.status(), path, response);
  }

  private async parse<T>(status: number, path: string, response: {
    json: () => Promise<unknown>;
  }): Promise<Result<T>> {
    let body: T | null = null;
    try {
      body = (await response.json()) as T;
    } catch {
      body = null;
    }
    if (status !== 200 && status !== 201 && status !== 403 && status !== 404 && status !== 409) {
      // Never include a response body here: it could quote a token, URL or input value.
      throw new Error(`Unexpected product API status ${status} for ${path}`);
    }
    return { status, body };
  }

  async me(): Promise<Me> {
    const result = await this.get<Me>('/api/me');
    if (result.status !== 200 || result.body === null) throw new Error(`GET /api/me returned ${result.status}`);
    return result.body;
  }

  async history(requestId: string): Promise<HistoryEntry[]> {
    const result = await this.get<HistoryEntry[]>(`/api/requests/${requestId}/history`);
    if (result.status !== 200 || !Array.isArray(result.body)) {
      throw new Error(`GET request history returned ${result.status}`);
    }
    return result.body;
  }

  async budgets(month: string): Promise<BudgetView[]> {
    const result = await this.get<BudgetView[]>(`/api/budgets?month=${encodeURIComponent(month)}`);
    if (result.status !== 200 || !Array.isArray(result.body)) {
      throw new Error(`GET budgets returned ${result.status}`);
    }
    return result.body;
  }

  async notifications(): Promise<NotificationPage> {
    const result = await this.get<NotificationPage>('/api/notifications?page=0&size=100');
    if (result.status !== 200 || result.body === null) {
      throw new Error(`GET notifications returned ${result.status}`);
    }
    return result.body;
  }

  async request(requestId: string): Promise<RequestView> {
    const result = await this.get<RequestView>(`/api/requests/${requestId}`);
    if (result.status !== 200 || result.body === null) {
      throw new Error(`GET request returned ${result.status}`);
    }
    return result.body;
  }

  async approve(requestId: string, version: number): Promise<Result<RequestView>> {
    return this.post<RequestView>(`/api/requests/${requestId}/approve`, { version });
  }
}
