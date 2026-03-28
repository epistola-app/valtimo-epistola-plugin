package app.epistola.valtimo.config;

import com.ritense.form.autodeployment.FormDefinitionDeploymentService;
import com.ritense.importer.ImportRequest;
import com.ritense.valtimo.contract.case_.CaseDefinitionId;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * AOP aspect that auto-deploys the Epistola retry form for each case definition.
 * <p>
 * Fires after each form import via {@code FormDefinitionImporter.import()}.
 * Uses a per-case Set to ensure the retry form is only deployed once per case,
 * regardless of how many form files the case has.
 * <p>
 * This runs during the form import phase of the Valtimo case deployment pipeline,
 * ensuring the form exists before process-links are resolved.
 */
@Aspect
@Slf4j
public class EpistolaFormAutoDeployAspect {

    private static final String RETRY_FORM_NAME = "epistola-retry-document";
    private static final String FORM_RESOURCE = "config/global/form/epistola-retry-document.form.json";

    private final FormDefinitionDeploymentService deploymentService;
    private final Pattern caseFilterPattern;
    private final boolean deployAll;
    private final boolean deployNone;
    private final Set<CaseDefinitionId> deployedCases = ConcurrentHashMap.newKeySet();
    private volatile String retryFormJson;

    public EpistolaFormAutoDeployAspect(
            FormDefinitionDeploymentService deploymentService,
            EpistolaProperties properties
    ) {
        this.deploymentService = deploymentService;

        String filter = properties.getRetryForm().getCaseFilter();
        if (filter == null || "all".equalsIgnoreCase(filter)) {
            this.deployAll = true;
            this.deployNone = false;
            this.caseFilterPattern = null;
        } else if ("none".equalsIgnoreCase(filter)) {
            this.deployAll = false;
            this.deployNone = true;
            this.caseFilterPattern = null;
        } else {
            this.deployAll = false;
            this.deployNone = false;
            try {
                this.caseFilterPattern = Pattern.compile(filter);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(
                        "Invalid regex for epistola.retry-form.case-filter: '" + filter + "'", e);
            }
        }
    }

    @After("execution(* com.ritense.form.service.FormDefinitionImporter.import(..)) && args(request)")
    public void deployRetryFormAfterFormImport(ImportRequest request) {
        CaseDefinitionId caseDefId = request.getCaseDefinitionId();
        if (caseDefId == null) {
            return;
        }
        if (!matchesCaseFilter(caseDefId.getKey())) {
            return;
        }
        if (!deployedCases.add(caseDefId)) {
            return; // already deployed for this case
        }

        try {
            String formJson = getRetryFormJson();
            if (formJson == null) {
                return; // resource missing, already logged at first load attempt
            }
            deploymentService.deploy(RETRY_FORM_NAME, formJson, caseDefId, false);
            log.info("Auto-deployed {} form for case {}", RETRY_FORM_NAME, caseDefId);
        } catch (Exception e) {
            log.error("Failed to auto-deploy {} form for case {}", RETRY_FORM_NAME, caseDefId, e);
        }
    }

    private boolean matchesCaseFilter(String caseKey) {
        if (deployAll) return true;
        if (deployNone) return false;
        return caseFilterPattern.matcher(caseKey).matches();
    }

    private String getRetryFormJson() {
        if (retryFormJson == null) {
            try {
                retryFormJson = new ClassPathResource(FORM_RESOURCE)
                        .getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("Epistola retry form resource not found on classpath: {}. " +
                        "Retry form auto-deployment is disabled.", FORM_RESOURCE);
                retryFormJson = ""; // sentinel to avoid re-attempting
            }
        }
        return retryFormJson.isEmpty() ? null : retryFormJson;
    }
}
