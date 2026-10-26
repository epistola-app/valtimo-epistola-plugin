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
package app.epistola.valtimo.service;

import app.epistola.client.api.CatalogsApi;
import app.epistola.client.model.CatalogDto;
import app.epistola.client.model.CatalogListResponse;
import app.epistola.client.model.PageMeta;
import app.epistola.valtimo.client.EpistolaApiClientFactory;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * The tenant's catalogs, for tests of {@link EpistolaServiceImpl} that read through a catalog:
 * since contract 2.0 a read first finds the catalog (and its highest release) by listing them.
 */
final class CatalogFixtures {

    static final String REGISTRY = "local";
    static final String NAMESPACE = "valtimo";
    static final String RELEASE = "1.0.0";

    private CatalogFixtures() {
    }

    static CatalogDto catalog(String key) {
        return catalog(REGISTRY, NAMESPACE, key, RELEASE);
    }

    static CatalogDto catalog(String registry, String namespace, String key, String highestVersion) {
        return new CatalogDto(registry, namespace, key, key, true, null, null, null, highestVersion, null, null);
    }

    /** Stub {@code factory} to list {@code catalogs} for {@code tenant}, on one page. */
    static CatalogsApi stubCatalogs(EpistolaApiClientFactory factory, String tenant, CatalogDto... catalogs) {
        CatalogsApi catalogsApi = mock(CatalogsApi.class);
        lenient().when(factory.createCatalogsApi(any(), any())).thenReturn(catalogsApi);
        lenient().when(catalogsApi.listCatalogs(eq(tenant), anyInt(), anyInt(), isNull(), isNull()))
                .thenReturn(new CatalogListResponse(List.of(catalogs), new PageMeta(0, 100, catalogs.length, 1)));
        return catalogsApi;
    }
}
