package com.github.grepHammerspace.api;

import com.github.grepHammerspace.stateStore.UserState;
import com.github.grepHammerspace.stateStore.UserStateStore;
import com.github.grepHammerspace.tailscale.TailscaleIdentityHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.cdimascio.dotenv.Dotenv;
import org.openqa.selenium.Keys;

import javax.inject.Inject;
import java.io.IOException;

import static com.github.grepHammerspace.util.DriverUtils.*;

@Path("/otj-services")
@Produces("application/json")
@Consumes("application/json")
public class OtjServicesResource {
    private static final Logger log = LoggerFactory.getLogger(OtjServicesResource.class);
    private static final String LOGIN_URL = "https://education.oneadvanced.com/";

    private final UserStateStore userStateStore;
    private final Dotenv dotenv = Dotenv.load();

    @Inject
    public OtjServicesResource(UserStateStore userStateStore) {
        this.userStateStore = userStateStore;
    }

    @Context
    private HttpServletRequest request;

    @GET
    public Response testGetTailscaleId() {
        try {
            String userId = resolveUserState();
            log.info(" Received request {} from user {}",request,userId);
            return Response.ok("{\"user\": \"" + userId + "\"}").build();
        } catch (IOException e) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/prepare-browser")
    public Response prepareBrowser() throws InterruptedException {
        String userId;
        try {
            userId = resolveUserState();
        } catch (IOException e) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }

        WebDriver driver = new FirefoxDriver();
        driver.get(LOGIN_URL);

        waitForElement(driver, By.name("emailOrUsername")).sendKeys(dotenv.get("username") + Keys.RETURN);
        waitForElement(driver, By.name("username")).sendKeys(dotenv.get("username") + Keys.RETURN);
        waitForElement(driver, By.id("user_password")).sendKeys(dotenv.get("OApassword"), Keys.RETURN);
        waitForElement(driver, By.id("otp"));

        return Response.ok().build();
    }

    private String resolveUserState() throws IOException {
        String userId = TailscaleIdentityHelper.getUser(request);
        userStateStore.createUserState(userId);
        return userId;
    }
}
