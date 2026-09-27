package integration;

import com.github.grepHammerspace.web.Driver;
import com.github.grepHammerspace.web.OtjSubmitResult;
import com.github.grepHammerspace.web.PrepareResult;

import java.io.IOException;
import java.util.List;

// The one place in the suite that keeps a password on purpose. ServerHooks' log guard hunts for
// these sentinel values.
public class FakeDriver implements Driver {
    private PrepareResult nextResult = PrepareResult.mfaPushSent();
    private IOException nextFailure = null;

    private volatile String preparedUsername;
    private volatile String preparedPassword;
    private volatile String completedMfaCode;
    private volatile String submittedUserId;
    private volatile String submittedLearnerId;
    private volatile int prepareCalls = 0;

    public void willReturn(PrepareResult result) {
        this.nextResult = result;
    }

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
