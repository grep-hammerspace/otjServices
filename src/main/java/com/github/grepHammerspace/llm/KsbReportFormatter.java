package com.github.grepHammerspace.llm;

import java.time.LocalDate;

public class KsbReportFormatter {

    private KsbReportFormatter() {}

    public static String format(KsbMatchResult result, String userId, String learnerId) {
        StringBuilder sb = new StringBuilder();
        sb.append("KSB Matching Report\n");
        sb.append("Generated: ").append(LocalDate.now()).append("\n");
        sb.append("User: ").append(userId).append(" / Learner: ").append(learnerId).append("\n");
        sb.append("Activities considered: ").append(result.totalActivitiesConsidered()).append("\n");
        sb.append("Activities matched to a KSB: ").append(result.matches().size()).append("\n");
        sb.append("=".repeat(60)).append("\n\n");

        if (result.matches().isEmpty()) {
            sb.append("No activities could be matched to a KSB.\n");
            return sb.toString();
        }

        for (KsbMatch match : result.matches()) {
            sb.append("Date: ").append(match.activityDate()).append("\n");
            sb.append("Activity: ").append(match.activitySummary()).append("\n");
            sb.append("Matched KSBs: ").append(String.join(", ", match.matchedKsbs())).append("\n");
            sb.append("Explanation: ").append(match.explanation()).append("\n\n");
        }

        return sb.toString();
    }
}
