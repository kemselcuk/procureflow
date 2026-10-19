import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { WritableSignal, signal } from '@angular/core';
import { WorkspaceSession } from '../workspace/workspace-session';
import { WorkspaceProfile } from '../workspace/workspace-api';
import { RequestFormPage } from './request-form-page';
import { requestView, COMPANY_A, COMPANY_B, IDENTITY_A, IDENTITY_B, DEPARTMENT_A } from './request-fixtures';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

describe('Request form page', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<RequestFormPage>;
  let ownerKey: WritableSignal<string | null>;
  let profileSignal: WritableSignal<WorkspaceProfile | null>;
  let generation: WritableSignal<number>;
  let employee: WritableSignal<boolean>;
  let navigate: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    ownerKey = signal<string | null>(`${COMPANY_A}\u0000${IDENTITY_A}`);
    generation = signal(3);
    profileSignal = signal<WorkspaceProfile | null>({
      identityId: IDENTITY_A,
      membershipId: 'aaaaaaaa-3333-4333-8333-333333333333',
      companyId: COMPANY_A,
      companyName: 'Larkvane',
      departmentId: DEPARTMENT_A,
      departmentName: 'engineering',
      version: 3,
      roles: ['EMPLOYEE'],
      managedDepartmentIds: [],
    });
    employee = signal(true);
    TestBed.configureTestingModule({
      imports: [RequestFormPage],
      providers: [
        provideRouter([{ path: 'requests/:id', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: WorkspaceSession,
          useValue: {
            ownerKey,
            generation,
            profile: profileSignal,
            isEmployee: employee,
            canReachApprovals: signal(false),
            refresh: async () => profileSignal(),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(RequestFormPage);
    fixture.detectChanges();
    navigate = vi.fn().mockResolvedValue(true);
    TestBed.inject(Router).navigate = navigate as unknown as Router['navigate'];
  });

  afterEach(() => http.verify());

  function element(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    return element().textContent ?? '';
  }

  function input(selector: string): HTMLInputElement | HTMLTextAreaElement {
    return element().querySelector(selector) as HTMLInputElement;
  }

  function fill(title: string, justification: string, amount: string): void {
    const titleInput = input('#request-title');
    titleInput.value = title;
    titleInput.dispatchEvent(new Event('input'));
    const justificationInput = input('#request-justification');
    justificationInput.value = justification;
    justificationInput.dispatchEvent(new Event('input'));
    const amountInput = input('#request-amount');
    amountInput.value = amount;
    amountInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function clickCreate(): void {
    const button = Array.from(element().querySelectorAll('button')).find((candidate) =>
      (candidate.textContent ?? '').includes('Create draft'),
    ) as HTMLButtonElement;
    button.click();
    fixture.detectChanges();
  }

  function expectCreate(): TestRequest {
    const request = http.expectOne('/api/requests');
    expect(request.request.method).toBe('POST');
    return request;
  }

  function profileB(): WorkspaceProfile {
    return {
      identityId: IDENTITY_B,
      membershipId: 'bbbbbbbb-3333-4333-8333-333333333333',
      companyId: COMPANY_B,
      companyName: 'Other company',
      departmentId: 'bbbbbbbb-4444-4444-8444-444444444444',
      departmentName: 'operations',
      version: 1,
      roles: ['EMPLOYEE'],
      managedDepartmentIds: [],
    };
  }

  function switchOwner(key: string | null, nextProfile: WorkspaceProfile | null): void {
    ownerKey.set(key);
    profileSignal.set(nextProfile);
    // WorkspaceSession increments its monotonic generation on every owner transition.
    generation.update((value) => value + 1);
    fixture.detectChanges();
  }

  it('shows the current server department and sends no request on an invalid submit', () => {
    expect(text()).toContain('engineering');
    expect(text()).toContain('no department selector');
    clickCreate();
    expect(http.match('/api/requests').length).toBe(0);
    expect(text()).toContain('Enter a title');
    expect(text()).toContain('Enter a justification');
    expect(text()).toContain('Enter an amount');
  });

  it.each(['0', '1.001', '1000000000000', '1,00', '-5'])(
    'rejects the invalid amount %s without silently rounding it',
    (amount) => {
      fill('Laptop replacement', 'Battery failure', amount);
      clickCreate();
      expect(http.match('/api/requests').length).toBe(0);
      expect(text()).toContain('at most 2 decimals');
    },
  );

  it('creates a draft with the exact body, a UUID key and one request per pending click', () => {
    fill('Laptop replacement', 'Battery failure', '999999999999.99');
    clickCreate();
    const request = expectCreate();
    const key = request.request.headers.get('Idempotency-Key');
    expect(key).toMatch(UUID_PATTERN);
    expect(request.request.body).toEqual({
      title: 'Laptop replacement',
      justification: 'Battery failure',
      amount: 999999999999.99,
    });
    expect(request.request.headers.keys().sort()).toEqual(['Idempotency-Key']);

    const button = Array.from(element().querySelectorAll('button')).find((candidate) =>
      (candidate.textContent ?? '').includes('Creating draft'),
    ) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    button.click();
    expect(http.match('/api/requests').length).toBe(0);

    request.flush(requestView({ id: '99999999-9999-4999-8999-999999999999' }));
    fixture.detectChanges();
    expect(navigate).toHaveBeenCalledWith(['/requests', '99999999-9999-4999-8999-999999999999']);
  });

  it('reuses the same retry key for an unchanged save after an ambiguous network failure', () => {
    fill('Laptop replacement', 'Battery failure', '800.00');
    clickCreate();
    const first = expectCreate();
    const key = first.request.headers.get('Idempotency-Key')!;
    first.flush('network down', { status: 0, statusText: 'Unknown Error' });
    fixture.detectChanges();
    expect(text()).toContain('may or may not have saved this change');
    expect(text()).toContain('reuses the same retry key');

    clickCreate();
    const second = expectCreate();
    expect(second.request.headers.get('Idempotency-Key')).toBe(key);
    expect(second.request.body).toEqual({
      title: 'Laptop replacement',
      justification: 'Battery failure',
      amount: 800,
    });
    second.flush(requestView({ id: '99999999-9999-4999-8999-999999999999' }));
    fixture.detectChanges();
    expect(navigate).toHaveBeenCalled();
  });

  it('uses a new retry key after the user changes the payload', () => {
    fill('Laptop replacement', 'Battery failure', '800.00');
    clickCreate();
    const first = expectCreate();
    const originalKey = first.request.headers.get('Idempotency-Key');
    first.flush('network down', { status: 0, statusText: 'Unknown Error' });
    fixture.detectChanges();

    fill('Replacement laptop', 'Battery failure', '800.00');
    clickCreate();
    const second = expectCreate();
    expect(second.request.headers.get('Idempotency-Key')).not.toBe(originalKey);
    second.flush('Server error', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(text()).toContain('The server could not confirm whether this change was saved');
  });

  it('clears a reused key after idempotency_key_reused and starts a new attempt', () => {
    fill('Laptop replacement', 'Battery failure', '800.00');
    clickCreate();
    const first = expectCreate();
    const originalKey = first.request.headers.get('Idempotency-Key');
    first.flush({ status: 409, title: 'Conflict', code: 'idempotency_key_reused' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(text()).toContain('was already used for different content');

    clickCreate();
    const second = expectCreate();
    expect(second.request.headers.get('Idempotency-Key')).not.toBe(originalKey);
    second.flush(requestView({ id: '99999999-9999-4999-8999-999999999999' }));
  });

  it('keeps entered values and shows a conflict message on an ordinary 409', () => {
    fill('Laptop replacement', 'Battery failure', '800.00');
    clickCreate();
    expectCreate().flush(
      { status: 409, title: 'Conflict', code: 'retryable_conflict' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    expect(text()).toContain('temporary database conflict');
    expect(input('#request-title').value).toBe('Laptop replacement');
    expect(input('#request-justification').value).toBe('Battery failure');
    expect(input('#request-amount').value).toBe('800.00');
  });

  it('clears the form and the retry attempt when the active identity changes', () => {
    fill('Laptop replacement', 'Battery failure', '800.00');
    clickCreate();
    const pending = expectCreate();
    pending.flush('network down', { status: 0, statusText: 'Unknown Error' });
    fixture.detectChanges();

    switchOwner(`${COMPANY_B}\u0000${IDENTITY_B}`, profileB());
    expect(input('#request-title').value).toBe('');
    expect(input('#request-justification').value).toBe('');
    expect(input('#request-amount').value).toBe('');
    expect(text()).toContain('form was cleared to avoid mixing drafts');

    fill('New draft', 'New justification', '10.00');
    clickCreate();
    const next = expectCreate();
    next.flush('network down', { status: 0, statusText: 'Unknown Error' });
    fixture.detectChanges();
    clickCreate();
    const retried = expectCreate();
    retried.flush(requestView({ id: '99999999-9999-4999-8999-999999999999' }));
  });

  it('blocks creation for a non-employee and keeps text entered while the role is inactive', () => {
    employee.set(false);
    fixture.detectChanges();
    fill('Laptop replacement', 'Battery failure', '800.00');
    expect(text()).toContain('requires the active EMPLOYEE role');
    expect(
      (Array.from(element().querySelectorAll('button')).find((candidate) =>
        (candidate.textContent ?? '').includes('Create draft'),
      ) as HTMLButtonElement).disabled,
    ).toBe(true);
    clickCreate();
    expect(http.match('/api/requests').length).toBe(0);

    employee.set(true);
    fixture.detectChanges();
    clickCreate();
    expectCreate().flush(requestView({ id: '99999999-9999-4999-8999-999999999999' }));
  });

  it('disables the whole fieldset while a create is pending', () => {
    fill('Laptop replacement', 'Battery failure', '800.00');
    clickCreate();
    const request = expectCreate();
    expect(element().querySelector('fieldset')?.hasAttribute('disabled')).toBe(true);
    request.flush(requestView({ id: '99999999-9999-4999-8999-999999999999' }));
    fixture.detectChanges();
  });

  it.each(['success', 'error'] as const)(
    'ignores a create %s that resolves after another owner becomes current',
    (outcome) => {
      fill('Owner A draft', 'Owner A justification', '800.00');
      clickCreate();
      const oldCreate = expectCreate();

      switchOwner(`${COMPANY_B}\u0000${IDENTITY_B}`, profileB());
      if (outcome === 'success') {
        oldCreate.flush(requestView({ id: '99999999-9999-4999-8999-999999999999' }));
      } else {
        oldCreate.flush('Server error', { status: 500, statusText: 'Server Error' });
      }
      fixture.detectChanges();

      expect(navigate).not.toHaveBeenCalled();
      expect(text()).not.toContain('could not confirm whether this change was saved');
      expect(input('#request-title').value).toBe('');

      fill('Owner B draft', 'Owner B justification', '10.00');
      clickCreate();
      expectCreate().flush(requestView({ id: '99999999-9999-4999-8999-999999999998' }));
      fixture.detectChanges();
      expect(navigate).toHaveBeenCalledWith(['/requests', '99999999-9999-4999-8999-999999999998']);
    },
  );

  it('ignores a create result from before logout even when the same owner signs in again', () => {
    fill('Laptop replacement', 'Battery failure', '800.00');
    clickCreate();
    const oldCreate = expectCreate();

    switchOwner(null, null);
    switchOwner(`${COMPANY_A}\u0000${IDENTITY_A}`, profileSignal() ?? {
      identityId: IDENTITY_A,
      membershipId: 'aaaaaaaa-3333-4333-8333-333333333333',
      companyId: COMPANY_A,
      companyName: 'Larkvane',
      departmentId: DEPARTMENT_A,
      departmentName: 'engineering',
      version: 3,
      roles: ['EMPLOYEE'],
      managedDepartmentIds: [],
    });

    oldCreate.flush(requestView());
    fixture.detectChanges();
    expect(navigate).not.toHaveBeenCalled();
    expect(text()).not.toContain('could not confirm whether this change was saved');
  });

  it('cancels the create subscription when the page is destroyed', () => {
    fill('Laptop replacement', 'Battery failure', '800.00');
    clickCreate();
    expectCreate();

    fixture.destroy();
    expect(http.match('/api/requests').length).toBe(0);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('clears the draft and invalidates its attempt when EMPLOYEE authority is lost', () => {
    fill('Laptop replacement', 'Battery failure', '800.00');
    clickCreate();
    const pending = expectCreate();

    employee.set(false);
    fixture.detectChanges();
    expect(input('#request-title').value).toBe('');
    expect(input('#request-justification').value).toBe('');
    expect(input('#request-amount').value).toBe('');
    expect(text()).toContain('form was cleared');
    expect(text()).toContain('cleared for security');
    expect(text()).toContain('draft creation stays blocked');
    expect(text()).not.toContain('entered text is kept');

    pending.flush(requestView());
    fixture.detectChanges();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('keeps retry inputs when an unchanged membership profile is read again', () => {
    fill('Laptop replacement', 'Battery failure', '800.00');
    clickCreate();
    expectCreate().flush('network down', { status: 0, statusText: 'Unknown Error' });
    fixture.detectChanges();
    expect(text()).toContain('reuses the same retry key');

    profileSignal.set({ ...profileSignal()! });
    fixture.detectChanges();

    expect(input('#request-title').value).toBe('Laptop replacement');
    expect(input('#request-justification').value).toBe('Battery failure');
    expect(input('#request-amount').value).toBe('800.00');
    expect(text()).toContain('reuses the same retry key');
  });
});
