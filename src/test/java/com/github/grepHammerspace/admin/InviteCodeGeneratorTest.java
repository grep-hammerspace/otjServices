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

    /**
     * The alphabet exists to survive being dictated over the phone and typed on a handset.
     * {@code I}/{@code 1} and {@code O}/{@code 0} are the pairs people get wrong, so none of the
     * four may ever appear.
     */
    @Test
    void generate_neverEmitsAmbiguousGlyphs() {
        // Only the random groups are under test. The fixed "OTJ-" prefix legitimately contains an
        // 'O' — it is never transcribed by hand, because it is the same on every code.
        String thousandGroups = IntStream.range(0, 1000)
                .mapToObj(i -> generator.generate().substring("OTJ-".length()).replace("-", ""))
                .reduce("", String::concat);

        for (char ambiguous : new char[]{'I', 'O', '0', '1'}) {
            assertEquals(-1, thousandGroups.indexOf(ambiguous),
                    "'" + ambiguous + "' is too easily transcribed as its lookalike");
        }
    }

    /**
     * Not a randomness test — that's SecureRandom's job. This catches the generator being wired
     * to a constant seed or returning a cached value, which would silently reissue live codes.
     */
    @Test
    void generate_doesNotRepeatItself() {
        Set<String> codes = new HashSet<>();
        IntStream.range(0, 1000).forEach(i -> codes.add(generator.generate()));

        assertEquals(1000, codes.size(), "1000 draws from a 40-bit space should not collide");
    }
}
