import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SystemInfo, SystemStatus } from './system-status';

type ConnectionState =
  | { kind: 'loading' }
  | { kind: 'ready'; info: SystemInfo }
  | { kind: 'error' };

@Component({
  selector: 'app-root',
  standalone: true,
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly system = inject(SystemStatus);
  private readonly destroyRef = inject(DestroyRef);
  readonly connection = signal<ConnectionState>({ kind: 'loading' });

  constructor() {
    this.loadStatus();
  }

  loadStatus(): void {
    this.connection.set({ kind: 'loading' });
    this.system.load().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (info) => this.connection.set({ kind: 'ready', info }),
      error: () => this.connection.set({ kind: 'error' }),
    });
  }
}
