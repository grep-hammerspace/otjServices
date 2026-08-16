package com.github.grepHammerspace.bind;

import com.github.grepHammerspace.admin.AdminIdentityFilter;
import com.github.grepHammerspace.admin.AdminInviteResource;
import com.mongodb.client.MongoDatabase;
import dagger.Component;

import javax.inject.Singleton;

/**
 * Dagger component for the admin process.
 *
 * <p>Reuses {@link AppModule} for the Mongo connection so both processes talk to the same database
 * the same way, and adds {@link AdminModule} for the allowlist. It exposes only the admin resource
 * and its filter — nothing here can reach an {@code OtjServicesResource}, a driver or the LLM.
 */
@Singleton
@Component(modules = {AppModule.class, AdminModule.class})
public interface AdminComponent {
    AdminInviteResource adminInviteResource();
    AdminIdentityFilter adminIdentityFilter();
    MongoDatabase mongoDatabase();
}
