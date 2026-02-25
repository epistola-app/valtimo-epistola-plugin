package app.epistola.valtimo.web.rest.dto;

import java.util.Map;

public record ValidateMappingRequest(
        Map<String, Object> dataMapping
) {
}
