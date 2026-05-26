package integration;

import com.github.grepHammerspace.api.OtjServicesResource;
import com.mongodb.client.MongoDatabase;
import dagger.Component;

import javax.inject.Singleton;

@Singleton
@Component(modules = TestAppModule.class)
public interface TestAppComponent {
    OtjServicesResource otjServicesResource();
    MongoDatabase mongoDatabase();
}
