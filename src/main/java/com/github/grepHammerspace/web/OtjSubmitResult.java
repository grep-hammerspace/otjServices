package com.github.grepHammerspace.web;

import java.util.List;

public record OtjSubmitResult(List<String> posted, List<String> failed) {
    public boolean nothingToPost() { return posted.isEmpty() && failed.isEmpty(); }
    public boolean allPosted()     { return !posted.isEmpty() && failed.isEmpty(); }
    public boolean allFailed()     { return posted.isEmpty() && !failed.isEmpty(); }
    public boolean partial()       { return !posted.isEmpty() && !failed.isEmpty(); }
}
