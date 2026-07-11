package com.github.grepHammerspace.api;

import com.github.grepHammerspace.db.ActivityLogRepository;
import com.github.grepHammerspace.db.UserRepository;
import com.github.grepHammerspace.db.model.ActivityLog;
import com.github.grepHammerspace.db.model.User;
import com.github.grepHammerspace.llm.KsbMatchResult;
import com.github.grepHammerspace.llm.KsbMatcherService;
import com.github.grepHammerspace.llm.KsbReportFormatter;
import com.github.grepHammerspace.llm.exception.LlmException;
import com.github.grepHammerspace.llm.exception.LlmRateLimitException;
import com.github.grepHammerspace.tailscale.TailscaleIdentityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/** JAX-RS resource for the KSB-matching "sweeper" endpoint. */
@Path("/ksb-services")
public class KsbServicesResource {
    private static final Logger log = LoggerFactory.getLogger(KsbServicesResource.class);

    private final TailscaleIdentityService tailscaleIdentityService;
    private final UserRepository userRepository;
    private final ActivityLogRepository activityLogRepository;
    private final KsbMatcherService ksbMatcherService;

    @Inject
    public KsbServicesResource(TailscaleIdentityService tailscaleIdentityService,
                                UserRepository userRepository,
                                ActivityLogRepository activityLogRepository,
                                KsbMatcherService ksbMatcherService) {
        this.tailscaleIdentityService = tailscaleIdentityService;
        this.userRepository = userRepository;
        this.activityLogRepository = activityLogRepository;
        this.ksbMatcherService = ksbMatcherService;
    }

    @GET
    @Path("/prepare-doc")
    @Produces(MediaType.TEXT_PLAIN)
    public Response prepareDoc(@Context HttpServletRequest request) {
        String userId;
        try {
            userId = tailscaleIdentityService.getUser(request);
            log.info("Received request from user {} to do {}", userId, "prepare-doc");
        } catch (IOException e) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Error: " + e.getMessage()).build();
        }

        User user = userRepository.findByUserId(userId);
        if (user == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("No registered user found for this Tailscale identity. Call POST /otj-services/register first.")
                    .build();
        }

        List<ActivityLog> logs = activityLogRepository.getAllLogsFor(userId);
        if (logs.isEmpty()) {
            return Response.ok("No activity logs found for this user.\n")
                    .header("Content-Disposition", attachmentHeader())
                    .build();
        }

        KsbMatchResult result;
        try {
            result = ksbMatcherService.matchActivitiesToKsbs(logs, userId, user.learnerId());
        } catch (LlmRateLimitException e) {
            return Response.status(429).entity("Error: " + e.getMessage()).build();
        } catch (LlmException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Error: " + e.getMessage()).build();
        } catch (Exception e) {
            String msg = "Unexpected error calling LLM: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error(msg, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Error: " + msg).build();
        }

        String report = KsbReportFormatter.format(result, userId, user.learnerId());

        log.info("Request complete — {} of {} activities matched to a KSB", result.matches().size(), result.totalActivitiesConsidered());

        return Response.ok(report)
                .header("Content-Disposition", attachmentHeader())
                .build();
    }

    private static String attachmentHeader() {
        return "attachment; filename=\"ksb-report-" + LocalDate.now() + ".txt\"";
    }
}
