package com.pipemend.pipeline.verifiers;

import com.pipemend.pipeline.validation.Whitespace;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * PARSE_NUMBER (docs/02 section 4). (1) Only one pound sign is removed, and only in unit_price; any other currency
 * symbol is CURRENCY_NOT_CONVERTIBLE. (2) Normalizing strip(original) with the proposed separators gives the proposed
 * number. (3) The sign never changes. (4) One separator followed by exactly three digits is AMBIGUOUS_NUMBER. (5)
 * quantity and customer_id have no fraction, and customer_id is written without decimals.
 */
public final class ParseNumberVerifier implements Verifier {

    private static final char POUND = '\u00A3';
    private static final Set<String> DECIMAL_SEPARATORS = Set.of(".", ",");
    private static final Set<String> GROUPING_SEPARATORS = Set.of(".", ",", " ");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern ONE_SEPARATOR_THEN_THREE_DIGITS = Pattern.compile("[^.,]*[.,]\\d{3}");

    @Override
    public VerificationResult verify(VerificationInput input) {
        String original = input.original();
        if (original == null) {
            return mismatch("PARSE_NUMBER needs a value");
        }
        String s = Whitespace.strip(original);
        boolean unitPrice = input.field().equals("unit_price");
        for (char c : s.toCharArray()) {
            if (Character.getType(c) == Character.CURRENCY_SYMBOL && !(unitPrice && c == POUND)) {
                // Removing it would be a currency conversion made up by the pipeline.
                return VerificationResult.failure(
                        VerificationCode.CURRENCY_NOT_CONVERTIBLE, "Only the pound sign of unit_price can be removed");
            }
        }

        Map<String, @Nullable Object> parameters = input.correction().parameters();
        Object decimal = parameters.get("decimalSeparator");
        Object grouping = parameters.get("groupingSeparator");
        if (!(decimal instanceof String decimalSeparator && DECIMAL_SEPARATORS.contains(decimalSeparator))
                || !(grouping == null
                        || (grouping instanceof String text
                                && GROUPING_SEPARATORS.contains(text)
                                && !text.equals(decimal)))) {
            return VerificationResult.failure(
                    VerificationCode.INVALID_PARAMETERS, "The separators are missing, unknown or equal");
        }
        String groupingSeparator = (String) grouping;

        // docs/02 l.104: at most one pound sign, at the start or the end, and one sign before the digits.
        String body = s;
        char sign = 0;
        if (!body.isEmpty() && (body.charAt(0) == '-' || body.charAt(0) == '+')) {
            sign = body.charAt(0);
            body = body.substring(1);
        }
        boolean pound = !body.isEmpty() && body.charAt(0) == POUND;
        if (pound) {
            body = body.substring(1);
            if (sign == 0 && !body.isEmpty() && (body.charAt(0) == '-' || body.charAt(0) == '+')) {
                sign = body.charAt(0);
                body = body.substring(1);
            }
        }
        if (!body.isEmpty() && body.charAt(body.length() - 1) == POUND) {
            if (pound) {
                return mismatch("Only one pound sign can be removed");
            }
            body = body.substring(0, body.length() - 1);
        }

        long separators = body.chars().filter(c -> c == '.' || c == ',').count();
        if (separators == 1 && ONE_SEPARATOR_THEN_THREE_DIGITS.matcher(body).matches()) {
            return ambiguous();
        }
        int point = body.indexOf(decimalSeparator);
        if (point != body.lastIndexOf(decimalSeparator)) {
            return mismatch("More than one decimal separator");
        }
        String integerPart = point < 0 ? body : body.substring(0, point);
        String fraction = point < 0 ? "" : body.substring(point + 1);
        if (groupingSeparator != null && !groups(integerPart, groupingSeparator)) {
            // "12,34" or "2,5" with grouping "," would multiply the value: two readings (docs/02 l.131, ADR-0008).
            return ambiguous();
        }
        String digits = groupingSeparator == null ? integerPart : integerPart.replace(groupingSeparator, "");
        if (!DIGITS.matcher(digits).matches()
                || (point >= 0 && !DIGITS.matcher(fraction).matches())) {
            return mismatch("The value does not normalize to a number");
        }
        BigDecimal value = new BigDecimal((sign == '-' ? "-" : "") + digits + (point < 0 ? "" : "." + fraction));

        String proposedText = input.proposed();
        BigDecimal proposed;
        try {
            proposed = proposedText == null ? null : new BigDecimal(proposedText);
        } catch (NumberFormatException e) {
            proposed = null;
        }
        if (proposed == null) {
            return mismatch("The proposed value is not a number");
        }
        boolean newSign = sign == 0 && (proposedText.startsWith("-") || proposedText.startsWith("+"));
        if (newSign || (value.signum() != 0 && value.signum() != proposed.signum())) {
            return VerificationResult.failure(VerificationCode.SIGN_CHANGED, "The sign of the number changed");
        }
        if (value.compareTo(proposed) != 0) {
            return mismatch("Normalizing with the separators does not give the proposed value");
        }
        if (!unitPrice && value.stripTrailingZeros().scale() > 0) {
            return mismatch("quantity and customer_id cannot have a fraction");
        }
        if (input.field().equals("customer_id")
                && !proposedText.equals(value.toBigIntegerExact().toString())) {
            return mismatch("customer_id is written without decimals");
        }
        return VerificationResult.success();
    }

    /** B2: a first group of 1 to 3 digits, then exactly 3 digits after every grouping separator. */
    private static boolean groups(String integerPart, String separator) {
        if (!integerPart.contains(separator)) {
            return true;
        }
        String[] groups = integerPart.split(Pattern.quote(separator), -1);
        if (groups[0].isEmpty() || groups[0].length() > 3) {
            return false;
        }
        for (int i = 1; i < groups.length; i++) {
            if (groups[i].length() != 3) {
                return false;
            }
        }
        return true;
    }

    private static VerificationResult ambiguous() {
        return VerificationResult.failure(
                VerificationCode.AMBIGUOUS_NUMBER, "The separators admit more than one reading of the number");
    }

    private static VerificationResult mismatch(String detail) {
        return VerificationResult.failure(VerificationCode.VALUE_MISMATCH, detail);
    }
}
