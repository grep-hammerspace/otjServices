package integration;

import java.util.HashMap;
import java.util.Map;

/**
 * Thread-local key/value store used to pass data between Cucumber hooks and step definitions
 * within a single scenario.
 *
 * <p>Cucumber instantiates {@link ServerHooks} and {@link RegistrationSteps} as separate objects,
 * so they cannot share fields directly. This class bridges that gap: {@link ServerHooks} writes
 * the server base URL and {@link com.mongodb.client.MongoDatabase} handle at scenario start, and
 * step definition classes read them back when executing steps.
 *
 * <p>The {@link ThreadLocal} backing ensures each thread has its own isolated map, which keeps
 * scenarios safe if Cucumber is ever configured to run in parallel.
 */
public class ScenarioContext {
    private static final ThreadLocal<Map<String, Object>> STORE =
        ThreadLocal.withInitial(HashMap::new);

    /** Clears the store — called by {@link ServerHooks} at the start of each scenario. */
    public static void init()                  { STORE.get().clear(); }
    /** Stores a value under {@code key} for the current scenario. */
    public static void put(String k, Object v) { STORE.get().put(k, v); }
    /** Retrieves a value stored under {@code key}, or {@code null} if not set. */
    public static Object get(String k)         { return STORE.get().get(k); }
}
