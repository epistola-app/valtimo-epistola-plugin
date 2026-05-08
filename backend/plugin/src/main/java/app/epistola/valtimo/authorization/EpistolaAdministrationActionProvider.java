package app.epistola.valtimo.authorization;

import com.ritense.authorization.Action;
import com.ritense.authorization.ResourceActionProvider;

import java.util.List;

public class EpistolaAdministrationActionProvider implements ResourceActionProvider<EpistolaAdministration> {

    public static final Action<EpistolaAdministration> MANAGE = new Action<>("manage");

    @Override
    public List<Action<EpistolaAdministration>> getAvailableActions() {
        return List.of(MANAGE);
    }
}
