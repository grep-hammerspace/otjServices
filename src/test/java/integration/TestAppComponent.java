package integration;

import com.github.grepHammerspace.admin.AdminIdentityFilter;
import com.github.grepHammerspace.admin.AdminInviteResource;
import com.github.grepHammerspace.api.AccountResource;
import com.github.grepHammerspace.api.AuthResource;
import com.github.grepHammerspace.api.CryptoResource;
import com.github.grepHammerspace.api.OtjServicesResource;
import com.github.grepHammerspace.auth.AuthenticationFilter;
import com.github.grepHammerspace.auth.SessionTokenService;
import com.github.grepHammerspace.db.InviteCodeRepository;
import com.github.grepHammerspace.web.AzurePush;
import com.github.grepHammerspace.web.Keycloak;
import com.mongodb.client.MongoDatabase;
import dagger.Component;

import javax.inject.Singleton;

/**
 * Dagger component for the integration test object graph.
 *
 * <p>Mirrors {@link com.github.grepHammerspace.bind.AppComponent} but uses
 * {@link TestAppModule} so that the LLM, MongoDB and the two login drivers are test doubles.
 * A new component instance is built per scenario in {@link ServerHooks}, giving
 * each scenario a fresh, isolated set of singletons.
 */
@Singleton
@Component(modules = TestAppModule.class)
public interface TestAppComponent {
    OtjServicesResource otjServicesResource();

    /** The fakes behind the two prepare endpoints, so steps can assert on what they received. */
    @Keycloak FakeDriver keycloakDriver();
    @AzurePush FakeDriver azurePushDriver();

    AuthResource authResource();
    AccountResource accountResource();
    CryptoResource cryptoResource();
    AuthenticationFilter authenticationFilter();
    AdminInviteResource adminInviteResource();
    AdminIdentityFilter adminIdentityFilter();
    SessionTokenService sessionTokenService();
    InviteCodeRepository inviteCodeRepository();
    MongoDatabase mongoDatabase();
}
