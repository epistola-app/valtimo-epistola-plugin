package app.epistola.valtimo.authorization;

import com.ritense.authorization.permission.Permission;
import com.ritense.authorization.request.AuthorizationRequest;
import com.ritense.authorization.specification.AuthorizationSpecification;
import com.ritense.authorization.specification.AuthorizationSpecificationFactory;
import kotlin.jvm.functions.Function0;

import java.util.List;

@SuppressWarnings("deprecation")
public class EpistolaAdministrationSpecificationFactory implements AuthorizationSpecificationFactory<EpistolaAdministration> {

    @Override
    public AuthorizationSpecification<EpistolaAdministration> create(
            AuthorizationRequest<EpistolaAdministration> request,
            Function0<? extends List<Permission>> permissionSupplier
    ) {
        return new EpistolaAdministrationSpecification(request, permissionSupplier.invoke());
    }

    @Override
    public AuthorizationSpecification<EpistolaAdministration> create(
            AuthorizationRequest<EpistolaAdministration> request,
            List<Permission> permissions
    ) {
        return new EpistolaAdministrationSpecification(request, permissions);
    }

    @Override
    public boolean canCreate(AuthorizationRequest<?> request, Function0<? extends List<Permission>> permissionSupplier) {
        return EpistolaAdministration.class.equals(request.getResourceType());
    }

    @Override
    public boolean canCreate(AuthorizationRequest<?> request, List<Permission> permissions) {
        return EpistolaAdministration.class.equals(request.getResourceType());
    }
}
