package com.github.grepHammerspace.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AzureIdDriverTest {
    private static final String PROCESS_AUTH = "https://login.microsoftonline.com/common/SAS/ProcessAuth";

    private static final String KMSI_PAGE = """
            <html><head><title>Sign in to your account</title></head><body><script>
            $Config={"pgid":"KmsiInterrupt","urlPost":"/kmsi?ctx=SECRET-CTX",
            "sFT":"SECRET-FLOW-TOKEN","sCtx":"SECRET-CTX","canary":"SECRET-CANARY"};
            </script></body></html>
            """;

    @Test
    void namesTheStayedSignedInInterstitialAndWhereItPosts() {
        assertEquals(
                "pgid=KmsiInterrupt, forms=0, urlPost=https://login.microsoftonline.com/kmsi [ctx]",
                AzureIdDriver.describePage(KMSI_PAGE, PROCESS_AUTH));
    }

    @Test
    void neverIncludesTheFlowTokenOrContext() {
        String described = AzureIdDriver.describePage(KMSI_PAGE, PROCESS_AUTH);
        assertFalse(described.contains("SECRET"), described);
    }

    @Test
    void reportsMicrosoftsNumericErrorCode() {
        String errorPage = """
                <html><script>$Config={"pgid":"ConvergedError","iErrorCode":"50058",
                "strServiceExceptionMessage":"user@example.test is not signed in"};</script></html>
                """;

        String described = AzureIdDriver.describePage(errorPage, PROCESS_AUTH);
        assertTrue(described.endsWith(", errorCode=50058"), described);
        assertFalse(described.contains("example.test"), described);
    }

    @Test
    void describesAPageWithoutConfig() {
        assertEquals("pgid=null, forms=1, urlPost=none",
                AzureIdDriver.describePage("<form action=/x></form>", PROCESS_AUTH));
    }
}
