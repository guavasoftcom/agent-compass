export interface WindowOption {
  value: number;
  label: string;
}

export const WINDOWS: readonly WindowOption[] = [
  { value: 5, label: '5 minutes' },
  { value: 15, label: '15 minutes' },
  { value: 30, label: '30 minutes' },
  { value: 60, label: '1 hour' },
  { value: 60 * 8, label: '8 hours' },
  { value: 60 * 24, label: '24 hours' },
  { value: 60 * 24 * 7, label: '7 days' },
  { value: 60 * 24 * 30, label: '30 days' },
];
