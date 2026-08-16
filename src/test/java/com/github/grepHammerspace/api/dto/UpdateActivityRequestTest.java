package com.github.grepHammerspace.api.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The validation rules for {@code PUT /pending/{id}}, exercised without the HTTP layer.
 *
 *  <p>The rules exist so that a hand-edited row cannot end up less valid than a parsed one, which
 *  makes each one worth a case of its own. {@code edit_pending.feature} then proves that a failure
 *  here reaches the client as a 400 with the message in it. */
class UpdateActivityRequestTest {

    private static final DateTimeFormatter YYYY_MM_DD = DateTimeFormatter.ofPattern("uuuu/MM/dd");
    private static final String YESTERDAY = LocalDate.now().minusDays(1).format(YYYY_MM_DD);

    /** A request that passes every rule, so each test can vary exactly one thing. */
    private static UpdateActivityRequest valid() {
        return new UpdateActivityRequest(YESTERDAY, "09:30", 2, 30, "Paired on the auth filter");
    }

    private static String errorFor(UpdateActivityRequest request) {
        return request.normalised().validationError();
    }

    private static UpdateActivityRequest withDate(String date) {
        return new UpdateActivityRequest(date, "09:00", 1, 0, "Some work");
    }

    private static UpdateActivityRequest withTime(String time) {
        return new UpdateActivityRequest(YESTERDAY, time, 1, 0, "Some work");
    }

    private static UpdateActivityRequest withDuration(int hours, int minutes) {
        return new UpdateActivityRequest(YESTERDAY, "09:00", hours, minutes, "Some work");
    }

    private static UpdateActivityRequest withImpact(String impact) {
        return new UpdateActivityRequest(YESTERDAY, "09:00", 1, 0, impact);
    }

    /** Asserts every value is refused, and that the message says which field was at fault. */
    private static void allRejected(List<UpdateActivityRequest> requests, String expectedInMessage) {
        for (UpdateActivityRequest request : requests) {
            String error = errorFor(request);
            assertNotNull(error, "should have been rejected: " + request);
            assertTrue(error.contains(expectedInMessage),
                    "message should mention '" + expectedInMessage + "' but was: " + error);
        }
    }

    @Test
    void aFullyValidRequestHasNoError() {
        assertNull(errorFor(valid()));
    }

    @Test
    void anEmptyStartTimeIsValid() {
        assertNull(errorFor(withTime("")),
                "empty is the existing convention for an entry that never mentioned a time");
    }

    @Test
    void todayIsNotInTheFuture() {
        assertNull(errorFor(withDate(LocalDate.now().format(YYYY_MM_DD))));
    }

    @Test
    void aDateThatIsNotYyyySlashMmSlashDdIsRejected() {
        allRejected(List.of(withDate("2026-08-12"), withDate("12/08/2026"), withDate("2026/8/9"),
                withDate("not a date"), withDate(""), withDate("2026/08/12 09:00")), "activityDate");
    }

    @Test
    void aNullDateIsRejected() {
        assertNotNull(errorFor(withDate(null)));
    }

    @Test
    void aDateThatIsNotOnTheCalendarIsRejected() {
        allRejected(List.of(withDate("2026/02/30"), withDate("2026/13/01"), withDate("2025/02/29")),
                "real calendar date");
    }

    @Test
    void aFutureDateIsRejected() {
        String error = errorFor(withDate(LocalDate.now().plusDays(1).format(YYYY_MM_DD)));

        assertNotNull(error);
        assertTrue(error.contains("future"), error);
    }

    @Test
    void aTimeThatIsNotHhMmIsRejected() {
        allRejected(List.of(withTime("9:30"), withTime("0930"), withTime("25:00"),
                withTime("12:60"), withTime("midday")), "activityTime");
    }

    @Test
    void aStartTimeOutsideWorkingHoursIsRejected() {
        allRejected(List.of(withTime("08:59"), withTime("18:01"), withTime("23:30"), withTime("00:00")),
                "09:00 and 18:00");
    }

    @Test
    void theWorkingHourBoundsThemselvesAreAccepted() {
        assertNull(errorFor(withTime("09:00")));
        assertNull(errorFor(withTime("18:00")));
    }

    @Test
    void aNullTimeIsRejected() {
        assertNotNull(errorFor(withTime(null)));
    }

    @Test
    void anAbsurdHoursValueIsRejected() {
        allRejected(List.of(withDuration(-1, 30), withDuration(25, 0)), "hours");
    }

    @Test
    void aTwelveHourEntryIsAccepted() {
        assertNull(errorFor(withDuration(12, 0)),
                "the parser applies no duration ceiling, so neither does the edit path");
    }

    @Test
    void minutesOutsideZeroToFiftyNineAreRejected() {
        allRejected(List.of(withDuration(1, 60), withDuration(1, 90), withDuration(1, -1)), "minutes");
    }

    @Test
    void aZeroTotalDurationIsRejected() {
        String error = errorFor(withDuration(0, 0));

        assertNotNull(error, "a zero-duration row is what missing_duration exists to reject");
        assertTrue(error.contains("zero minutes"), error);
    }

    @Test
    void aBlankDescriptionIsRejected() {
        allRejected(List.of(withImpact(""), withImpact("   "), withImpact("\n\t")), "activityImpact");
    }

    @Test
    void aNullDescriptionIsRejected() {
        assertNotNull(errorFor(withImpact(null)));
    }

    @Test
    void aDescriptionOverAThousandCharactersIsRejected() {
        String error = errorFor(withImpact("x".repeat(1001)));

        assertNotNull(error);
        assertTrue(error.contains("1000"), error);
    }

    @Test
    void aDescriptionOfExactlyAThousandCharactersIsAccepted() {
        assertNull(errorFor(withImpact("x".repeat(1000))));
    }

    @Test
    void normalisingCollapsesWhitespaceAndTrimsTheEdges() {
        UpdateActivityRequest normalised =
                new UpdateActivityRequest("  " + YESTERDAY + " ", " 09:00 ", 1, 0,
                        "  Read   the\n\nspec  ").normalised();

        assertEquals(YESTERDAY, normalised.activityDate());
        assertEquals("09:00", normalised.activityTime());
        assertEquals("Read the spec", normalised.activityImpact(),
                "what is stored is the tidied text, not what the caller happened to send");
    }

    @Test
    void normalisingLeavesNullsAlone() {
        UpdateActivityRequest normalised =
                new UpdateActivityRequest(null, null, 1, 0, null).normalised();

        assertNull(normalised.activityDate());
        assertNull(normalised.activityTime());
        assertNull(normalised.activityImpact());
    }

    @Test
    void aDescriptionThatIsOnlyOverTheLimitBeforeCollapsingIsAccepted() {
        String padded = "word " + " ".repeat(200) + "x".repeat(900);

        assertNull(errorFor(withImpact(padded)),
                "the length rule applies to what gets stored, which is the collapsed text");
    }
}
