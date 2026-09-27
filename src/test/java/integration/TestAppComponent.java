package integration;

import com.github.grepHammerspace.admin.AdminIdentityFilter;
import com.github.grepHammerspace.admin.AdminInviteResource;
import com.github.grepHammerspace.api.AccountResource;
import com.github.grepHammerspace.api.AuthResource;
import com.github.grepHammerspace.api.OtjServicesResource;
import com.github.grepHammerspace.auth.AuthenticationFilter;
import com.github.grepHammerspace.auth.SessionTokenService;
import com.github.grepHammerspace.db.InviteCodeRepository;
import com.github.grepHammerspace.web.AzurePush;
import com.github.grepHammerspace.web.Keycloak;
import com.mongodb.client.MongoDatabase;
import dagger.Component;

import javax.inject.Singleton;

@Singleton
@Component(modules = TestAppModule.class)
public interface TestAppComponent {
    OtjServicesResource otjServicesResource();

    @Keycloak FakeDriver keycloakDriver();
    @AzurePush FakeDriver azurePushDriver();

    AuthResource authResource();
    AccountResource accountResource();
    AuthenticationFilter authenticationFilter();
    AdminInviteResource adminInviteResource();
    AdminIdentityFilter adminIdentityFilter();
    SessionTokenService sessionTokenService();
    InviteCodeRepository inviteCodeRepository();
    MongoDatabase mongoDatabase();
}
