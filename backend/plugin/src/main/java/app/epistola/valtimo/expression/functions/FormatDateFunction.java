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
package app.epistola.valtimo.expression.functions;

import app.epistola.valtimo.expression.ExpressionContext;
import app.epistola.valtimo.expression.EpistolaExpressionFunction;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;

/**
 * Expression function for formatting dates.
 * <p>
 * Usage examples:
 * <pre>
 * expr:formatDate(#doc['invoiceDate'], 'dd-MM-yyyy')
 * expr:formatDate(#pv['dueDate'], 'MMMM d, yyyy')
 * </pre>
 */
public class FormatDateFunction implements EpistolaExpressionFunction {

    @Override
    public String name() {
        return "formatDate";
    }

    @Override
    public String description() {
        return "Format a date to a string using a DateTimeFormatter pattern";
    }

    /**
     * Format a {@link LocalDate} using the given pattern.
     */
    public String execute(ExpressionContext ctx, LocalDate date, String pattern) {
        return date.format(DateTimeFormatter.ofPattern(pattern));
    }

    /**
     * Format a {@link LocalDateTime} using the given pattern.
     */
    public String execute(ExpressionContext ctx, LocalDateTime dateTime, String pattern) {
        return dateTime.format(DateTimeFormatter.ofPattern(pattern));
    }

    /**
     * Parse a date string (ISO-8601) and format it using the given pattern.
     */
    public String execute(ExpressionContext ctx, String dateStr, String pattern) {
        TemporalAccessor parsed;
        if (dateStr.contains("T")) {
            parsed = LocalDateTime.parse(dateStr);
        } else {
            parsed = LocalDate.parse(dateStr);
        }
        return DateTimeFormatter.ofPattern(pattern).format(parsed);
    }
}
