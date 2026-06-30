// Public surface of the shared dashboard API. Consumers import everything from
// here (`from '../../api'`): the DTO types and the `fetchXxx` endpoints. The
// transport plumbing in `http.ts` is intentionally not re-exported.

export * from './types';
export * from './endpoints';
