package com.pipemend.pipeline.verifiers;

import com.pipemend.pipeline.schema.FieldRule.DateTimeRule;
import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.validation.Whitespace;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * PARSE_DATE (docs/02 section 4): (1) parameters.sourcePattern is a DateTimeFormatter pattern with hour and minute and
 * no zone or localized text; (2) parsing strip(original) with it in STRICT mode gives exactly the proposed value in the
 * canonical format; (3) if the pattern has a numeric day and month, the pattern with both swapped must not parse to
 * another date (AMBIGUOUS_DATE).
 */
public final class ParseDateVerifier implements Verifier {

    // The zone and localized-text letters of DateTimeFormatter; MMM, LLL and QQQ are text, while M, L and Q are
    // numbers.
    private static final Set<Character> ZONE_OR_TEXT = Set.of('z', 'Z', 'X', 'x', 'O', 'V', 'v', 'E', 'a', 'G', 'B');
    private static final Set<Character> TEXT_FROM_THREE = Set.of('M', 'L', 'Q', 'q');

    private record Run(char letter, int count) {}

    private final DateTimeFormatter canonical;

    public ParseDateVerifier(SchemaDefinition schema) {
        this.canonical = ((DateTimeRule) schema.field("invoice_date").rule()).formatter();
    }

    @Override
    public VerificationResult verify(VerificationInput input) {
        if (!(input.correction().parameters().get("sourcePattern") instanceof String pattern)) {
            return VerificationResult.failure(VerificationCode.INVALID_PARAMETERS, "sourcePattern is missing");
        }
        List<Run> runs = runs(pattern);
        if (runs == null || !allowed(runs)) {
            return VerificationResult.failure(
                    VerificationCode.INVALID_PARAMETERS,
                    "sourcePattern needs hour and minute and no zone or localized text");
        }
        DateTimeFormatter formatter;
        DateTimeFormatter swapped;
        try {
            formatter = strict(pattern, false);
            swapped = strict(pattern, true);
        } catch (IllegalArgumentException e) {
            return VerificationResult.failure(
                    VerificationCode.INVALID_PARAMETERS, "sourcePattern is not a valid pattern");
        }
        String original = input.original();
        LocalDateTime parsed = original == null ? null : parse(Whitespace.strip(original), formatter);
        if (parsed == null || !canonical.format(parsed).equals(input.proposed())) {
            return VerificationResult.failure(
                    VerificationCode.VALUE_MISMATCH, "Parsing with sourcePattern does not give the proposed value");
        }
        boolean dayAndMonth = runs.stream().anyMatch(run -> run.letter() == 'd')
                && runs.stream().anyMatch(run -> run.letter() == 'M');
        if (dayAndMonth) {
            LocalDateTime other = parse(Whitespace.strip(original), swapped);
            if (other != null && !other.equals(parsed)) {
                return VerificationResult.failure(
                        VerificationCode.AMBIGUOUS_DATE, "Day and month swapped also parse, to another date");
            }
        }
        return VerificationResult.success();
    }

    private static boolean allowed(List<Run> runs) {
        boolean hour = false;
        boolean minute = false;
        for (Run run : runs) {
            if (ZONE_OR_TEXT.contains(run.letter()) || (TEXT_FROM_THREE.contains(run.letter()) && run.count() >= 3)) {
                return false;
            }
            hour |= run.letter() == 'H' && run.count() <= 2;
            minute |= run.letter() == 'm' && run.count() == 2;
        }
        return hour && minute;
    }

    /** The pattern letters outside quoted literals, as runs; null when a quote never closes. */
    private static @Nullable List<Run> runs(String pattern) {
        List<Run> runs = new ArrayList<>();
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && Character.isLetter(c)) {
                int start = i;
                while (i + 1 < pattern.length() && pattern.charAt(i + 1) == c) {
                    i++;
                }
                runs.add(new Run(c, i - start + 1));
            }
        }
        return quoted ? null : runs;
    }

    /**
     * STRICT mode with y replaced by u: with yyyy, STRICT would require an era (docs/02 section 4). With {@code swap},
     * d and M trade places, for the ambiguity check.
     */
    private static DateTimeFormatter strict(String pattern, boolean swap) {
        StringBuilder converted = new StringBuilder(pattern.length());
        boolean quoted = false;
        for (char c : pattern.toCharArray()) {
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == 'y') {
                c = 'u';
            } else if (!quoted && swap && (c == 'd' || c == 'M')) {
                c = c == 'd' ? 'M' : 'd';
            }
            converted.append(c);
        }
        return DateTimeFormatter.ofPattern(converted.toString()).withResolverStyle(ResolverStyle.STRICT);
    }

    /** Missing seconds default to :00; a pattern without a full date or time does not parse. */
    private static @Nullable LocalDateTime parse(String value, DateTimeFormatter formatter) {
        try {
            return LocalDateTime.parse(value, formatter);
        } catch (DateTimeException e) {
            return null;
        }
    }
}
