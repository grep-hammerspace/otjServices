package integration;

import java.util.HashMap;
import java.util.Map;

public class ScenarioContext {
    private static final ThreadLocal<Map<String, Object>> STORE =
        ThreadLocal.withInitial(HashMap::new);

    public static void init()                  { STORE.get().clear(); }
    public static void put(String k, Object v) { STORE.get().put(k, v); }
    public static Object get(String k)         { return STORE.get().get(k); }
}
