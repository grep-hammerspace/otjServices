package com.github.grepHammerspace.tailscale;

import jakarta.servlet.http.HttpServletRequest;
import javax.inject.Inject;
import java.io.IOException;

public class TailscaleIdentityServiceImpl implements TailscaleIdentityService {
    @Inject public TailscaleIdentityServiceImpl() {}

    @Override
    public String getUser(HttpServletRequest request) throws IOException {
        return TailscaleIdentityHelper.getUser(request);
    }
}
