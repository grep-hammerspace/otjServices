package com.github.grepHammerspace.bind;

import com.github.grepHammerspace.admin.AdminIdentityFilter;
import com.github.grepHammerspace.admin.AdminInviteResource;
import com.mongodb.client.MongoDatabase;
import dagger.Component;

import javax.inject.Singleton;

@Singleton
@Component(modules = {AppModule.class, AdminModule.class})
public interface AdminComponent {
    AdminInviteResource adminInviteResource();
    AdminIdentityFilter adminIdentityFilter();
    MongoDatabase mongoDatabase();
}
