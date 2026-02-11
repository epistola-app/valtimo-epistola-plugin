package app.epistola.valtimo.web.rest.dto;

import java.util.List;

public record ValidateMappingResponse(
        boolean valid,
        List<String> missingRequiredFields
) {
}
