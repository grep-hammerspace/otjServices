package com.github.grepHammerspace.bind;

import com.github.grepHammerspace.admin.AdminAllowlist;
import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;

@Module
public class AdminModule {
    @Provides
    @Singleton
    AdminAllowlist provideAdminAllowlist() {
        return AdminAllowlist.fromEnv();
    }
}
