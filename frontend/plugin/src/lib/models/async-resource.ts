/*
 * Copyright 2025 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */

export interface AsyncResource<T> {
  data: T;
  loading: boolean;
  error: string | null;
}

export function initialResource<T>(empty: T): AsyncResource<T> {
  return { data: empty, loading: false, error: null };
}

export function loadingResource<T>(current: T): AsyncResource<T> {
  return { data: current, loading: true, error: null };
}

export function successResource<T>(data: T): AsyncResource<T> {
  return { data, loading: false, error: null };
}

export function errorResource<T>(current: T, error: string): AsyncResource<T> {
  return { data: current, loading: false, error };
}
