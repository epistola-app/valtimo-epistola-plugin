// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2
package com.ritense.valtimo.epistola.documents

import org.springframework.data.jpa.repository.JpaRepository

interface StoredDocumentRepository : JpaRepository<StoredDocument, String>