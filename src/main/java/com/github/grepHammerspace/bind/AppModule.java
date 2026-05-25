package com.github.grepHammerspace.bind;

import com.github.grepHammerspace.stateStore.UserStateStore;
import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;

/**
 * Dagger module to provide application-wide dependencies.
 */

@Module
public class AppModule {

    @Provides
    @Singleton
    UserStateStore provideUserStateStore() {
        return new UserStateStore();
    }
}
