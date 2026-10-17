import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { map, Observable } from 'rxjs';

export interface SystemInfo {
  name: 'ProcureFlow';
  stage: 'foundation';
}

@Injectable({ providedIn: 'root' })
export class SystemStatus {
  private readonly http = inject(HttpClient);

  load(): Observable<SystemInfo> {
    return this.http.get<unknown>('/api/system').pipe(
      map((body) => {
        if (
          typeof body !== 'object' || body === null ||
          !('name' in body) || body.name !== 'ProcureFlow' ||
          !('stage' in body) || body.stage !== 'foundation'
        ) {
          throw new Error('Unexpected foundation API response');
        }
        return { name: body.name, stage: body.stage };
      }),
    );
  }
}
