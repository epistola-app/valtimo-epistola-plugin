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
package app.epistola.valtimo.authorization;

import com.ritense.authorization.Action;
import com.ritense.authorization.ResourceActionProvider;

import java.util.List;

public class EpistolaAdministrationActionProvider implements ResourceActionProvider<EpistolaAdministration> {

    public static final Action<EpistolaAdministration> MANAGE = new Action<>("manage");

    @Override
    public List<Action<EpistolaAdministration>> getAvailableActions() {
        return List.of(MANAGE);
    }
}
