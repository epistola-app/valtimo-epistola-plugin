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

import app.epistola.client.api.TemplatesApi;
import app.epistola.client.model.PageMeta;
import app.epistola.client.model.TemplateListResponse;
import app.epistola.client.model.TemplateSummaryDto;
import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.domain.TemplateInfo;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EpistolaServiceImplPaginationTest {

    private static final String BASE_URL = "http://epistola.example";
    private static final String API_KEY = "key";
    private static final String TENANT = "tenant";
    private static final String CATALOG = "default";

    @Test
    void getTemplatesFetchesAllPagesForDropdownOptions() {
        EpistolaApiClientFactory factory = mock(EpistolaApiClientFactory.class);
        TemplatesApi templatesApi = mock(TemplatesApi.class);
        when(factory.createTemplatesApi(BASE_URL, API_KEY)).thenReturn(templatesApi);

        when(templatesApi.listTemplates(eq(TENANT), eq(CATALOG), isNull(), eq(0), eq(100), isNull(), isNull()))
                .thenReturn(new TemplateListResponse(
                        List.of(template("template-1", "Template 1")),
                        new PageMeta(0, 100, 2, 2)
                ));
        when(templatesApi.listTemplates(eq(TENANT), eq(CATALOG), isNull(), eq(1), eq(100), isNull(), isNull()))
                .thenReturn(new TemplateListResponse(
                        List.of(template("template-2", "Template 2")),
                        new PageMeta(1, 100, 2, 2)
                ));

        EpistolaServiceImpl service = new EpistolaServiceImpl(factory);

        List<TemplateInfo> templates = service.getTemplates(BASE_URL, API_KEY, TENANT, CATALOG);

        assertEquals(List.of("template-1", "template-2"), templates.stream().map(TemplateInfo::id).toList());
        verify(templatesApi).listTemplates(eq(TENANT), eq(CATALOG), isNull(), eq(0), eq(100), isNull(), isNull());
        verify(templatesApi).listTemplates(eq(TENANT), eq(CATALOG), isNull(), eq(1), eq(100), isNull(), isNull());
    }

    private static TemplateSummaryDto template(String id, String name) {
        OffsetDateTime now = OffsetDateTime.now();
        return new TemplateSummaryDto(id, TENANT, name, now, now);
    }
}
