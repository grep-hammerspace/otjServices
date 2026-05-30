package com.github.grepHammerspace.llm;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class ContentDiffer {

    private ContentDiffer() {}

    /**
     * Returns the lines in {@code newContent} that are absent from {@code lastContent}.
     * Returns {@code null} when there is nothing new (i.e. the two are identical or the diff
     * produces only blank lines).
     * When {@code lastContent} is null or blank every non-blank line in {@code newContent} is
     * considered new.
     */
    public static String computeDiff(String lastContent, String newContent) {
        if (lastContent == null || lastContent.isBlank()) {
            return newContent;
        }

        Set<String> oldLines = new HashSet<>(Arrays.asList(lastContent.split("\n")));

        String diff = Arrays.stream(newContent.split("\n"))
                .filter(line -> !line.isBlank() && !oldLines.contains(line))
                .collect(Collectors.joining("\n"));

        return diff.isBlank() ? null : diff;
    }
}
