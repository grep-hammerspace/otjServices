package integration;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;

/**
 * JUnit Platform suite that drives the Cucumber integration tests.
 *
 * <p>This class contains no code — its annotations are the entire configuration:
 * <ul>
 *   <li>{@code @Suite} tells JUnit to treat this as a suite rather than a regular test class.</li>
 *   <li>{@code @IncludeEngines("cucumber")} delegates execution to the Cucumber engine.</li>
 *   <li>{@code @SelectClasspathResource("features")} points Cucumber at {@code src/test/resources/features/}.</li>
 *   <li>{@code @ConfigurationParameter(GLUE_PROPERTY_NAME, "integration")} tells Cucumber which
 *       package to scan for step definitions and lifecycle hooks.</li>
 * </ul>
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "integration")
public class CucumberIT {}
