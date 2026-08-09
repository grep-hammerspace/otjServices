package com.github.grepHammerspace.bind;

import com.github.grepHammerspace.admin.AdminAllowlist;
import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;

/**
 * Bindings that exist only in the admin process.
 *
 * <p>Kept out of {@link AppModule} so the main API cannot accidentally acquire a dependency on
 * admin configuration — if this binding ever appears in the API's graph, something has been wired
 * wrongly.
 */
@Module
public class AdminModule {

    @Provides
    @Singleton
    AdminAllowlist provideAdminAllowlist() {
        return AdminAllowlist.fromEnv();
    }
}
