package com.github.grepHammerspace.bind;

import com.github.grepHammerspace.api.OtjServicesResource;
import dagger.Component;

import javax.inject.Singleton;

@Singleton
@Component(modules = AppModule.class)
public interface AppComponent {
    OtjServicesResource otjServicesResource();
}
