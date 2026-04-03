package app.epistola.valtimo.expression;

import java.util.List;

/**
 * DTO describing an expression function and its overloads, for the REST API.
 */
public record ExpressionFunctionInfo(
        String name,
        String description,
        List<OverloadInfo> overloads
) {

    /**
     * Describes a single execute() overload with its arguments and return type.
     */
    public record OverloadInfo(
            List<ArgumentInfo> arguments,
            String returnType
    ) {}

    /**
     * Describes a single argument of an execute() overload.
     */
    public record ArgumentInfo(
            String name,
            String type
    ) {}
}
