package com.github.grepHammerspace.bind;

import com.github.grepHammerspace.api.AccountResource;
import com.github.grepHammerspace.api.AuthResource;
import com.github.grepHammerspace.api.OtjServicesResource;
import com.github.grepHammerspace.auth.AuthenticationFilter;
import com.mongodb.client.MongoDatabase;
import dagger.Component;

import javax.inject.Singleton;

@Singleton
@Component(modules = AppModule.class)
public interface AppComponent {
    OtjServicesResource otjServicesResource();
    AuthResource authResource();
    AccountResource accountResource();
    AuthenticationFilter authenticationFilter();
    MongoDatabase mongoDatabase();
}
