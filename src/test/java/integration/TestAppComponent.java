package integration;

import com.github.grepHammerspace.api.OtjServicesResource;
import com.mongodb.client.MongoDatabase;
import dagger.Component;

import javax.inject.Singleton;

/**
 * Dagger component for the integration test object graph.
 *
 * <p>Mirrors {@link com.github.grepHammerspace.bind.AppComponent} but uses
 * {@link TestAppModule} so that Tailscale identity and MongoDB are test doubles.
 * A new component instance is built per scenario in {@link ServerHooks}, giving
 * each scenario a fresh, isolated set of singletons.
 */
@Singleton
@Component(modules = TestAppModule.class)
public interface TestAppComponent {
    OtjServicesResource otjServicesResource();
    MongoDatabase mongoDatabase();
}
