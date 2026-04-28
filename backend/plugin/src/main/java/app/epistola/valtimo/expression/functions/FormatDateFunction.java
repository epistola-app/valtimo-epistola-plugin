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
