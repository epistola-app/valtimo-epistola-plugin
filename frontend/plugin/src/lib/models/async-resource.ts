export interface AsyncResource<T> {
  data: T;
  loading: boolean;
  error: string | null;
}

export function initialResource<T>(empty: T): AsyncResource<T> {
  return {data: empty, loading: false, error: null};
}

export function loadingResource<T>(current: T): AsyncResource<T> {
  return {data: current, loading: true, error: null};
}

export function successResource<T>(data: T): AsyncResource<T> {
  return {data, loading: false, error: null};
}

export function errorResource<T>(current: T, error: string): AsyncResource<T> {
  return {data: current, loading: false, error};
}
