package integration;

import com.github.grepHammerspace.api.AccountResource;
import com.github.grepHammerspace.api.OtjServicesResource;
import com.github.grepHammerspace.db.UserRepository;
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

    AccountResource accountResource();
    UserRepository userRepository();
    MongoDatabase mongoDatabase();
}
