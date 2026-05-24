package com.github.grepHammerspace.app;

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


import java.io.IOException;

import static com.github.grepHammerspace.util.DriverUtils.*;

@Path("/otj-services")
@Produces("application/json")
@Consumes("application/json")
public class OtjServicesResource {
    private static final Logger log = LoggerFactory.getLogger(OtjServicesResource.class);

    private final TailscaleIdentityHelper tailscaleIdHelper;
    private static final String LOGIN_URL = "https://education.oneadvanced.com/";
    Dotenv dotenv = Dotenv.load();

    public OtjServicesResource() {
        this.tailscaleIdHelper = new TailscaleIdentityHelper();
    }

    @Context
    private HttpServletRequest request;

    @GET
    public Response testGetTailscaleId() {
        try {
            log.info("Attempting to get identity for request from {}:{}", request.getRemoteAddr(), request.getRemotePort());
            String user = TailscaleIdentityHelper.getUser(request);
            return Response.ok("{\"user\": \"" + user + "\"}").build();
        } catch (IOException e) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/prepare-browser")
    public Response prepareBrowser() throws InterruptedException {
        WebDriver driver = new FirefoxDriver();
        driver.get(LOGIN_URL);

//        _wait_for_element(driver, By.NAME, "emailOrUsername").send_keys(username +
//                        _wait_for_element(driver, By.NAME, "username").send_keys(Keys.RETURN)
//                _wait_for_element(driver, By.ID, "password").send_keys(OApasswd + Keys.RET
//                        _wait_for_element(driver, By.ID, "otp")  # block until OTP page is loaded
        waitForElement(driver, By.name("emailOrUsername")).sendKeys(dotenv.get("username") + Keys.RETURN);
        waitForElement(driver, By.name("username")).sendKeys(dotenv.get("username") + Keys.RETURN);
        waitForElement(driver, By.id("user_password")).sendKeys(dotenv.get("OApassword"), Keys.RETURN);
        waitForElement(driver, By.id("otp"));

        return Response.ok().build();
    }

    private void prepareBrowserForUser(String username){
        // Check if valid session, if nto create one
        //  then rum the browser preparer commands and write the driver to that users session
    }
}
