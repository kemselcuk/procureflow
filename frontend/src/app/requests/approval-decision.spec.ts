import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { WritableSignal, signal } from '@angular/core';
import type { Mock } from 'vitest';
import { WorkspaceSession } from '../workspace/workspace-session';
import { WorkspaceProfile, WorkspaceRole } from '../workspace/workspace-api';
import { ApprovalDecision } from './approval-decision';
import { RequestView } from './request-api';
import {
  COMPANY_A,
  DEPARTMENT_A,
  IDENTITY_A,
  IDENTITY_B,
  REQUEST_A,
  REQUEST_B,
  requestView,
} from './request-fixtures';

function managerProfile(roles: readonly WorkspaceRole[] = ['DEPARTMENT_MANAGER']): WorkspaceProfile {
  return {
    identityId: IDENTITY_B,
    membershipId: 'bbbbbbbb-3333-4333-8333-333333333333',
    companyId: COMPANY_A,
    companyName: 'Larkvane',
    departmentId: DEPARTMENT_A,
    departmentName: 'department management',
    version: 3,
    roles,
    managedDepartmentIds: [DEPARTMENT_A],
  };
}

function pendingRequest(overrides: Partial<RequestView> = {}): RequestView {
  return requestView({
    status: 'AWAITING_DEPARTMENT_APPROVAL',
    requesterIdentityId: IDENTITY_A,
    departmentId: DEPARTMENT_A,
    amount: 800,
    version: 2,
    ...overrides,
  });
}

describe('Approval decision', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<ApprovalDecision>;
  let ownerKey: WritableSignal<string | null>;
  let generation: WritableSignal<number>;
  let changed: Mock<() => void>;

  beforeEach(() => {
    ownerKey = signal<string | null>(`${COMPANY_A}\u0000${IDENTITY_B}`);
    generation = signal(4);
    TestBed.configureTestingModule({
      imports: [ApprovalDecision],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: WorkspaceSession,
          useValue: {
            ownerKey,
            generation,
            profile: signal(managerProfile()),
            isEmployee: signal(false),
            canReachApprovals: signal(true),
            refresh: async () => managerProfile(),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ApprovalDecision);
    fixture.componentRef.setInput('request', pendingRequest());
    fixture.componentRef.setInput('profile', managerProfile());
    fixture.detectChanges();
    changed = vi.fn();
    fixture.componentInstance.changed.subscribe(() => changed());
  });

  afterEach(() => http.verify());

  function element(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    return element().textContent ?? '';
  }

  function button(label: string): HTMLButtonElement {
    const found = Array.from(element().querySelectorAll('button')).find((candidate) =>
      (candidate.textContent ?? '').trim().includes(label),
    );
    if (!found) throw new Error(`Button not found: ${label}`);
    return found as HTMLButtonElement;
  }

  function replaceRequest(request: RequestView): void {
    fixture.componentRef.setInput('request', request);
    fixture.detectChanges();
  }

  function rejectCurrent(id: string, reason: string): TestRequest {
    button('Reject request').click();
    fixture.detectChanges();
    const textarea = element().querySelector('textarea') as HTMLTextAreaElement;
    textarea.value = reason;
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    button('Confirm rejection').click();
    fixture.detectChanges();
    const request = http.expectOne(`/api/requests/${id}/reject`);
    expect(request.request.method).toBe('POST');
    return request;
  }

  it('ignores an approval response after the presented request version changes', () => {
    button('Approve department step').click();
    const stale = http.expectOne(`/api/requests/${REQUEST_A}/approve`);
    expect(stale.request.body).toEqual({ version: 2 });

    replaceRequest(pendingRequest({ version: 3, title: 'Reloaded request' }));
    stale.flush(requestView({ status: 'APPROVED', version: 3 }));
    fixture.detectChanges();

    expect(changed).not.toHaveBeenCalled();
    expect(text()).not.toContain('could not confirm whether this change was saved');
    expect(text()).not.toContain('does not allow this action');

    button('Approve department step').click();
    const current = http.expectOne(`/api/requests/${REQUEST_A}/approve`);
    expect(current.request.body).toEqual({ version: 3 });
    current.flush(requestView({ status: 'APPROVED', version: 4 }));
    fixture.detectChanges();
    expect(changed).toHaveBeenCalledTimes(1);
  });

  it.each([
    [
      'success',
      (request: TestRequest) => request.flush(requestView({ status: 'APPROVED', version: 3 })),
    ],
    [
      'error',
      (request: TestRequest) => request.flush('Server error', { status: 500, statusText: 'Server Error' }),
    ],
  ] as const)('ignores a late approval %s from the previous request target', (_label, respond) => {
    button('Approve department step').click();
    const stale = http.expectOne(`/api/requests/${REQUEST_A}/approve`);

    replaceRequest(pendingRequest({ id: REQUEST_B, title: 'B request' }));
    respond(stale);
    fixture.detectChanges();

    expect(changed).not.toHaveBeenCalled();
    expect(text()).not.toContain('could not confirm whether this change was saved');

    button('Approve department step').click();
    const current = http.expectOne(`/api/requests/${REQUEST_B}/approve`);
    expect(current.request.body).toEqual({ version: 2 });
    current.flush(requestView({ id: REQUEST_B, status: 'APPROVED', version: 3 }));
    fixture.detectChanges();
    expect(changed).toHaveBeenCalledTimes(1);
  });

  it('does not let an older response overwrite a newer target result', () => {
    button('Approve department step').click();
    const oldApproval = http.expectOne(`/api/requests/${REQUEST_A}/approve`);

    replaceRequest(pendingRequest({ id: REQUEST_B, title: 'B request' }));
    button('Approve department step').click();
    const newApproval = http.expectOne(`/api/requests/${REQUEST_B}/approve`);

    newApproval.flush(requestView({ id: REQUEST_B, status: 'APPROVED', version: 3 }));
    fixture.detectChanges();
    expect(changed).toHaveBeenCalledTimes(1);

    oldApproval.flush('Server error', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(changed).toHaveBeenCalledTimes(1);
    expect(text()).not.toContain('could not confirm whether this change was saved');
  });

  it.each([
    [
      'success',
      (request: TestRequest) => request.flush(requestView({ status: 'REJECTED', version: 3 })),
    ],
    [
      'error',
      (request: TestRequest) => request.flush('Server error', { status: 500, statusText: 'Server Error' }),
    ],
  ] as const)('ignores a late rejection %s from the previous request target', (_label, respond) => {
    const stale = rejectCurrent(REQUEST_A, 'Missing quotes');

    replaceRequest(pendingRequest({ id: REQUEST_B, title: 'B request' }));
    respond(stale);
    fixture.detectChanges();

    expect(changed).not.toHaveBeenCalled();
    expect(text()).not.toContain('could not confirm whether this change was saved');

    const current = rejectCurrent(REQUEST_B, 'B reason');
    expect(current.request.body).toEqual({ version: 2, reason: 'B reason' });
    current.flush(requestView({ id: REQUEST_B, status: 'REJECTED', version: 3 }));
    fixture.detectChanges();
    expect(changed).toHaveBeenCalledTimes(1);
  });
});
