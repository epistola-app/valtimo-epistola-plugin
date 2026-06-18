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

import {
  readPrefilledTaskId,
  PREFILLED_TASK_ID_SOURCE_KEY,
  PREFILLED_TASK_ID_DATA_KEY,
} from './prefilled-task-id';

describe('prefilled-task-id', () => {
  it('exposes the conventional source/data keys', () => {
    expect(PREFILLED_TASK_ID_SOURCE_KEY).toBe('epistola:taskId');
    expect(PREFILLED_TASK_ID_DATA_KEY).toBe('epistolaTaskId');
  });

  it('returns null for a missing root', () => {
    expect(readPrefilledTaskId(null)).toBeNull();
    expect(readPrefilledTaskId(undefined)).toBeNull();
  });

  it('reads the task id from a top-level carrier field by sourceKey', () => {
    const root = {
      form: {
        components: [
          {
            type: 'hidden',
            key: 'epistolaTaskId',
            properties: { sourceKey: 'epistola:taskId' },
            defaultValue: 'task-abc',
          },
        ],
      },
    };
    expect(readPrefilledTaskId(root)).toBe('task-abc');
  });

  it('finds the carrier nested inside panels/columns', () => {
    const root = {
      form: {
        components: [
          {
            type: 'panel',
            components: [
              { type: 'columns', columns: [{ components: [{ type: 'textfield', key: 'x' }] }] },
              {
                type: 'hidden',
                properties: { sourceKey: 'epistola:taskId' },
                defaultValue: 'task-nested',
              },
            ],
          },
        ],
      },
    };
    expect(readPrefilledTaskId(root)).toBe('task-nested');
  });

  it('falls back to submission data when the form scan finds nothing', () => {
    const root = {
      form: { components: [{ type: 'textfield', key: 'other' }] },
      data: { epistolaTaskId: 'task-from-data' },
    };
    expect(readPrefilledTaskId(root)).toBe('task-from-data');
  });

  it('prefers the prefilled form value over submission data', () => {
    const root = {
      form: {
        components: [{ properties: { sourceKey: 'epistola:taskId' }, defaultValue: 'task-form' }],
      },
      data: { epistolaTaskId: 'task-data' },
    };
    expect(readPrefilledTaskId(root)).toBe('task-form');
  });

  it('ignores an empty-string default value and falls through', () => {
    const root = {
      form: { components: [{ properties: { sourceKey: 'epistola:taskId' }, defaultValue: '' }] },
      data: { epistolaTaskId: 'task-data' },
    };
    expect(readPrefilledTaskId(root)).toBe('task-data');
  });

  it('returns null when neither the form nor the data carry the task id', () => {
    const root = { form: { components: [{ type: 'textfield', key: 'x' }] }, data: { x: 'y' } };
    expect(readPrefilledTaskId(root)).toBeNull();
  });
});
