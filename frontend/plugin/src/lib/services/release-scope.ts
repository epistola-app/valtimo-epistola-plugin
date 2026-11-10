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

/**
 * How a configurator scopes its lookups by a link's environment and release (ADR 0009). Kept free
 * of Angular so the utilities and their tests can use it without loading the HTTP client.
 */

/**
 * The environment a link generates in, as a configurator scopes its lookups by it. `undefined`
 * defers to the plugin configuration's default environment (the server applies it), a key names
 * one, and {@link ANY_ENVIRONMENT} says the link's environment is an expression only known at run
 * time.
 */
export type EnvironmentScope = string | typeof ANY_ENVIRONMENT | undefined;

/** No single environment applies: catalogs are read regardless of environment. */
export const ANY_ENVIRONMENT = Symbol('anyEnvironment');

/**
 * The release range a link renders, as a configurator scopes its lookups by it: `undefined` for
 * none, a range for a literal, and {@link ANY_RANGE} for an expression only known at run time.
 */
export type RangeScope = string | typeof ANY_RANGE | undefined;

/** No single range applies: the configurator designs against the highest release. */
export const ANY_RANGE = Symbol('anyRange');
