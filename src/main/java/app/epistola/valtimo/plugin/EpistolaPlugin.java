package app.epistola.valtimo.plugin;

import com.ritense.plugin.annotation.*;
import com.ritense.plugin.domain.EventType;
import com.ritense.processlink.domain.ActivityTypeWithEventName;
import lombok.RequiredArgsConstructor;
import org.operaton.bpm.engine.delegate.DelegateExecution;

@Plugin(
        key = "epistola",
        description = "Document generation using Epistola",
        title = "Epistola Document Suite"
)
@RequiredArgsConstructor
public class EpistolaPlugin {

    @SuppressWarnings({"unused"})
    @PluginProperty(
            title = "Tenant Id where the document template lives in",
            key = "tenantId",
            secret = false,
            required = false
    )
    private String tenantId;


    @PluginEvent(invokedOn = EventType.CREATE)
    void onPluginCreate() { // this is after the configuration properties were set
    }

    @PluginEvent(invokedOn = EventType.DELETE)
    void onPluginDelete() {
    }

    @SuppressWarnings("unused")
    @PluginEvent(invokedOn = EventType.UPDATE)
    void onPluginUpdate() {
    }

    @PluginAction(
            key = "generate-document",
            title = "Generate a new document",
            description = "Use this action to generate new documents.",
            activityTypes = {
                    ActivityTypeWithEventName.SERVICE_TASK_START
                    // TODO: what are activity types? should we add more?
                    // TODO: ideally we work with an external task so that we can resolve the task as soon as the document is generated
            }
    )
    public void generateDocument(
            DelegateExecution execution,
            @PluginActionProperty String message
    ) {
    }

}


