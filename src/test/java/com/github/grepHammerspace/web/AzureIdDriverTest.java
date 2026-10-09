package com.github.grepHammerspace.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // Shaped like the page in the log that prompted this: no form, posting to registerMfaMethods.
    private static final String PROOF_UP_PAGE = """
            <html><script>$Config={"pgid":"ConvergedProofUpRedirect","iErrorCode":"50203",
            "urlPost":"https://mysignins.microsoft.com/api/post/registerMfaMethods?ctx=SECRET-CTX",
            "sFT":"SECRET-FLOW-TOKEN","sProofUpDisplay":"+44 XXXXXXX12","sPOST_Username":"user@example.test",
            "urlSkipMfaRegistration":"https://login.microsoftonline.com/skip?ctx=SECRET-CTX",
            "oProofUp":{"iRemainingDays":14}};
            </script></html>
            """;

    @Test
    void describesTheProofUpPage() {
        assertEquals("pgid=ConvergedProofUpRedirect, forms=0, "
                        + "urlPost=https://mysignins.microsoft.com/api/post/registerMfaMethods [ctx], "
                        + "errorCode=50203",
                AzureIdDriver.describePage(PROOF_UP_PAGE, PROCESS_AUTH));
    }

    @Test
    void listsConfigKeyNamesSortedAndNested() {
        assertEquals("iErrorCode,iRemainingDays,oProofUp,pgid,sFT,sPOST_Username,sProofUpDisplay,"
                        + "urlPost,urlSkipMfaRegistration",
                AzureIdDriver.configKeys(PROOF_UP_PAGE));
    }

    @Test
    void configKeysCarryNoValues() {
        String keys = AzureIdDriver.configKeys(PROOF_UP_PAGE);
        assertFalse(keys.contains("SECRET"), keys);
        assertFalse(keys.contains("example.test"), keys);
        assertFalse(keys.contains("XXXX"), keys);
    }

    // Field names as a browser's "Not now" sent them in a recorded sign-in; values from the page.
    @Test
    void buildsTheNotNowFormFromTheProofUpPage() {
        String page = """
                <html><script>$Config={"pgid":"ConvergedProofUpRedirect","sFT":"FT-1",
                "sCtx":"CTX-1","canary":"CANARY-1","apiCanary":"API-CANARY"};</script></html>
                """;

        okhttp3.FormBody form = AzureIdDriver.proofUpSkipForm(page);

        assertNotNull(form);
        assertEquals(3, form.size());
        assertEquals("flowtoken", form.name(0));
        assertEquals("FT-1", form.value(0));
        assertEquals("ctx", form.name(1));
        assertEquals("CTX-1", form.value(1));
        assertEquals("canary", form.name(2));
        assertEquals("CANARY-1", form.value(2));
    }

    @Test
    void noNotNowFormWithoutTheFlowToken() {
        String page = """
                <html><script>$Config={"pgid":"ConvergedProofUpRedirect","sCtx":"CTX-1",
                "canary":"CANARY-1"};</script></html>
                """;
        assertNull(AzureIdDriver.proofUpSkipForm(page));
    }

    @Test
    void noNotNowFormWithoutConfig() {
        assertNull(AzureIdDriver.proofUpSkipForm("<form action=/x></form>"));
    }

    @Test
    void configKeysOfAPageWithoutConfig() {
        assertEquals("none", AzureIdDriver.configKeys("<form action=/x></form>"));
    }

    @Test
    void describesAPageWithoutConfig() {
        assertEquals("pgid=null, forms=1, urlPost=none",
                AzureIdDriver.describePage("<form action=/x></form>", PROCESS_AUTH));
    }
}
