import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import {
  BudgetApi,
  BudgetView,
  DepartmentView,
  budgetErrorCode,
  budgetErrorStatus,
  parseBudgetList,
  parseBudgetMonth,
  parseDepartmentList,
} from './budget-api';

const BUDGET_ID = '11111111-1111-4111-8111-111111111111';
const DEPARTMENT_ID = '22222222-2222-4222-8222-222222222222';

function budget(overrides: Partial<BudgetView> = {}): BudgetView {
  return {
    id: BUDGET_ID,
    departmentId: DEPARTMENT_ID,
    month: '2026-10-01',
    limitAmount: 1000,
    usedAmount: 400,
    remainingAmount: 600,
    version: 3,
    ...overrides,
  };
}

function department(overrides: Partial<DepartmentView> = {}): DepartmentView {
  return { id: DEPARTMENT_ID, name: 'Engineering', ...overrides };
}

describe('Budget API boundary', () => {
  let http: HttpTestingController;
  let api: BudgetApi;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(BudgetApi);
  });

  afterEach(() => http.verify());

  it('requests the exact month query and parses the canonical list without any company selector', () => {
    let received: readonly BudgetView[] | undefined;
    api.list('2026-10').subscribe((value) => (received = value));
    const request = http.expectOne((candidate) => candidate.url === '/api/budgets');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('month')).toBe('2026-10');
    expect(request.request.params.has('company')).toBe(false);
    expect(request.request.params.has('companyId')).toBe(false);
    request.flush([budget()]);
    expect(received).toEqual([budget()]);
  });

  it('puts exactly the limitAmount body to the department/month route', () => {
    let received: BudgetView | undefined;
    api.setLimit(DEPARTMENT_ID, '2026-11', 0).subscribe((value) => (received = value));
    const request = http.expectOne(
      (candidate) =>
        candidate.url === `/api/admin/departments/${DEPARTMENT_ID}/budgets/2026-11` &&
        candidate.method === 'PUT',
    );
    expect(request.request.body).toEqual({ limitAmount: 0 });
    expect(request.request.body).not.toHaveProperty('usedAmount');
    expect(request.request.body).not.toHaveProperty('companyId');
    request.flush(budget({ month: '2026-11-01', limitAmount: 0, usedAmount: 0, remainingAmount: 0, version: 1 }));
    expect(received?.limitAmount).toBe(0);
    expect(received?.usedAmount).toBe(0);
  });

  it('loads the backend-scoped department directory', () => {
    let received: readonly DepartmentView[] | undefined;
    api.departments().subscribe((value) => (received = value));
    const request = http.expectOne('/api/departments');
    expect(request.request.method).toBe('GET');
    request.flush([department()]);
    expect(received).toEqual([department()]);
  });

  it.each([
    ['a non-array list', null],
    ['a row without an id', [{ ...budget(), id: undefined }]],
    ['a non-UUID department', [budget({ departmentId: 'engineering' })]],
    ['a non-first day month', [budget({ month: '2026-10-02' })]],
    ['a negative limit', [budget({ limitAmount: -1 })]],
    ['a fractional version', [budget({ version: 1.5 })]],
    ['a non-numeric used amount', [{ ...budget(), usedAmount: '400.00' }]],
  ])('rejects %s instead of exposing untyped data', (_label, body) => {
    expect(() => parseBudgetList(body)).toThrow();
  });

  it('rejects malformed department rows', () => {
    expect(() => parseDepartmentList([{ id: DEPARTMENT_ID, name: '   ' }])).toThrow();
    expect(() => parseDepartmentList([{ id: 'not-a-uuid', name: 'Engineering' }])).toThrow();
    expect(() => parseDepartmentList({ id: DEPARTMENT_ID, name: 'Engineering' })).toThrow();
  });

  it.each([
    ['2000-01', '2000-01'],
    ['9999-12', '9999-12'],
    [' 2026-10 ', '2026-10'],
    ['1999-12', null],
    ['2026-13', null],
    ['2026-00', null],
    ['26-10', null],
    ['2026-1', null],
  ])('validates the supported backend month text %s', (input, expected) => {
    expect(parseBudgetMonth(input)).toBe(expected);
  });

  it('exposes only a whitelisted problem code and the HTTP status', () => {
    const failure = new HttpErrorResponse({
      status: 409,
      statusText: 'Conflict',
      error: { code: 'budget_below_usage', detail: '<img src=x>' },
    });
    expect(budgetErrorCode(failure)).toBe('budget_below_usage');
    expect(budgetErrorStatus(failure)).toBe(409);
    expect(budgetErrorCode(new HttpErrorResponse({ status: 500, error: { code: '<script>' } }))).toBeNull();
    expect(budgetErrorCode(new HttpErrorResponse({ status: 500, error: 'raw text' }))).toBeNull();
    expect(budgetErrorStatus(new Error('offline'))).toBeNull();
  });
});
