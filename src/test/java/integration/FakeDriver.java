package integration;

import com.github.grepHammerspace.web.Driver;
import com.github.grepHammerspace.web.OtjSubmitResult;
import com.github.grepHammerspace.web.PrepareResult;

import java.io.IOException;
import java.util.List;

/**
 * A {@link Driver} that records what it was handed and makes no network call.
 *
 * <p>Every other production class in {@link TestAppModule} is the real one, but the drivers
 * cannot be: they dial Keycloak and {@code login.microsoftonline.com} for real. This fake is what
 * lets a scenario prove the credentials on the request reach {@code prepare} unchanged, and that
 * the learner ID reaching {@code submitPendingOtjs} is the account's current one.
 *
 * <p>The recorded credentials are held so a test can assert on them. That is the one place in the
 * suite where a password is deliberately retained, and the sentinel values in
 * {@link ServerHooks#SECRETS} are what the log guard hunts for.
 */
public class FakeDriver implements Driver {

    private PrepareResult nextResult = PrepareResult.mfaPushSent();
    private IOException nextFailure = null;

    private volatile String preparedUsername;
    private volatile String preparedPassword;
    private volatile String completedMfaCode;
    private volatile String submittedUserId;
    private volatile String submittedLearnerId;
    private volatile int prepareCalls = 0;

    /** Scripts what the next {@code prepare} returns. */
    public void willReturn(PrepareResult result) {
        this.nextResult = result;
    }

    /** Scripts {@code prepare} to fail, as a wrong password would. */
    public void willFail(IOException failure) {
        this.nextFailure = failure;
    }

    @Override
    public PrepareResult prepare(String username, String password) throws IOException {
        prepareCalls++;
        this.preparedUsername = username;
        this.preparedPassword = password;
        if (nextFailure != null) throw nextFailure;
        return nextResult;
    }

    @Override
    public void completeMfa(String mfaToken) {
        this.completedMfaCode = mfaToken;
    }

    @Override
    public OtjSubmitResult submitPendingOtjs(String userId, String learnerId) {
        this.submittedUserId = userId;
        this.submittedLearnerId = learnerId;
        return new OtjSubmitResult(List.of("fake-posted-id"), List.of());
    }

    public String preparedUsername()   { return preparedUsername; }
    public String preparedPassword()   { return preparedPassword; }
    public String completedMfaCode()   { return completedMfaCode; }
    public String submittedUserId()    { return submittedUserId; }
    public String submittedLearnerId() { return submittedLearnerId; }
    public int prepareCalls()          { return prepareCalls; }
}
