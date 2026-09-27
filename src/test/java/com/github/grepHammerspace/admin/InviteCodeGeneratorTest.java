package com.github.grepHammerspace.admin;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InviteCodeGeneratorTest {
    private static final Pattern SHAPE = Pattern.compile("OTJ-[A-Z2-9]{4}-[A-Z2-9]{4}");

    private final InviteCodeGenerator generator = new InviteCodeGenerator();

    @Test
    void generate_producesTheDocumentedShape() {
        assertTrue(SHAPE.matcher(generator.generate()).matches(),
                "codes are documented as OTJ-XXXX-XXXX and operators read them aloud");
    }

    @Test
    void generate_neverEmitsAmbiguousGlyphs() {
        // The fixed "OTJ-" prefix legitimately contains an 'O'.
        String thousandGroups = IntStream.range(0, 1000)
                .mapToObj(i -> generator.generate().substring("OTJ-".length()).replace("-", ""))
                .reduce("", String::concat);

        for (char ambiguous : new char[]{'I', 'O', '0', '1'}) {
            assertEquals(-1, thousandGroups.indexOf(ambiguous),
                    "'" + ambiguous + "' is too easily transcribed as its lookalike");
        }
    }

    @Test
    void generate_doesNotRepeatItself() {
        Set<String> codes = new HashSet<>();
        IntStream.range(0, 1000).forEach(i -> codes.add(generator.generate()));

        assertEquals(1000, codes.size(), "1000 draws from a 40-bit space should not collide");
    }
}
