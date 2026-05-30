package com.github.grepHammerspace.stateStore;

import com.github.grepHammerspace.stateStore.UserStateStore;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestTemplate;

/**
 * Unit tests for {@link com.github.grepHammerspace.stateStore.UserStateStore}.
 * Each test gets a fresh store instance via {@code @BeforeEach} so there is no shared state.
 */
public class UserStateStoreTest {

    private UserStateStore userStateStore;
    private static final String DUMMY_ID = "dummyId";

    @BeforeEach
    public void setUp(){
        userStateStore = new UserStateStore();
    }

    @Test
    public void testCreateUserStateAddsUserState(){
        userStateStore.createUserState(DUMMY_ID);
        Assertions.assertNotNull(userStateStore.getStateForUser(DUMMY_ID));
    }

    @Test
    public void testGetStateForUs(){
        // Assert null when empty
        Assertions.assertNull(userStateStore.getStateForUser(DUMMY_ID));

        // Assert not null for valid user
        userStateStore.createUserState(DUMMY_ID);
        Assertions.assertTrue(userStateStore.getStateForUser(DUMMY_ID) != null);
    }

}
