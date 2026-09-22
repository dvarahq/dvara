/*
 * Copyright 2026 DVARA Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dvarahq.pii;

import com.dvarahq.core.pii.PiiEntityType;
import com.dvarahq.core.util.TtlLruCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Registry of compiled regex patterns for PII detection.
 *
 * <p>Maintains a set of built-in patterns for common PII types and supports
 * merging with workspace-specific custom patterns at scan time.</p>
 */
public class PiiPatternRegistry {

    /** NUL and SOH — control characters, so neither can occur in a label or a regex. */
    private static final char FIELD_SEPARATOR = '\u0000';
    private static final String ENTRY_SEPARATOR = "\u0001";

    /**
     * Compiled pattern sets, keyed on their contents. Bounded because the key is workspace-supplied.
     * An entry keyed on its own contents can never be stale, so the size and TTL bound memory, not
     * correctness, and a miss only recompiles one workspace's patterns.
     */
    private final TtlLruCache<List<PatternEntry>> mergedCache = new TtlLruCache<>(128, 3600);

    private final List<PatternEntry> builtInPatterns;

    public PiiPatternRegistry() {
        this.builtInPatterns = Collections.unmodifiableList(buildBuiltInPatterns());
    }

    /**
     * A compiled pattern entry mapping a regex to a PII entity type.
     */
    public record PatternEntry(Pattern pattern, PiiEntityType type, String label, double confidence) {}

    public List<PatternEntry> builtInPatterns() {
        return builtInPatterns;
    }

    /**
     * Merges the built-in patterns with a workspace's custom patterns.
     *
     * <p>The result is compiled once per distinct pattern set, not once per scan; this runs once per
     * content block on the request path. It is keyed on the patterns themselves rather than on a
     * workspace id, so an edited set is a new key and takes effect on the next scan. Only successful
     * compilations are cached: a malformed regex throws on every scan rather than being skipped,
     * because skipping it would leave the control quietly running without one of its rules.
     *
     * @param customPatterns map of label to regex for the custom patterns
     * @return the combined list of pattern entries
     */
    public List<PatternEntry> mergeWithCustom(Map<String, String> customPatterns) {
        if (customPatterns == null || customPatterns.isEmpty()) {
            return builtInPatterns;
        }
        String key = cacheKey(customPatterns);
        List<PatternEntry> cached = mergedCache.get(key);
        if (cached != null) {
            return cached;
        }
        var merged = new ArrayList<>(builtInPatterns);
        for (var entry : customPatterns.entrySet()) {
            merged.add(new PatternEntry(
                    Pattern.compile(entry.getValue()),
                    PiiEntityType.CUSTOM,
                    entry.getKey(),
                    0.9
            ));
        }
        List<PatternEntry> result = Collections.unmodifiableList(merged);
        mergedCache.put(key, result);
        return result;
    }

    /**
     * A stable identity for a pattern set: sorted, so equal maps in different orders share an
     * entry, and delimited with control characters, since a label or a regex can contain any
     * printable character and a comma would let {@code {"a,b": "x"}} and {@code {"a": "b,x"}}
     * collide.
     */
    private static String cacheKey(Map<String, String> customPatterns) {
        return customPatterns.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + FIELD_SEPARATOR + e.getValue())
                .collect(Collectors.joining(ENTRY_SEPARATOR));
    }

    private static List<PatternEntry> buildBuiltInPatterns() {
        var patterns = new ArrayList<PatternEntry>();

        // Email. A match may start only where a run of local-part characters starts, or where the previous
        // match ended (\G). A start inside a run finds nothing a start at the beginning of the run would not,
        // and trying each one costs the square of the run's length. \G keeps two addresses joined by a
        // local-part character, as in a@b.comx_y@c.com.
        patterns.add(new PatternEntry(
                Pattern.compile("(?:(?<![a-zA-Z0-9._%+\\-])|\\G)[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"),
                PiiEntityType.EMAIL, "email", 0.95));

        // US phone number (optional +1, area code, various separators). Never inside a longer run of digits, so
        // an order number, an invoice id or an epoch timestamp does not match part of itself as a phone.
        patterns.add(new PatternEntry(
                Pattern.compile("(?<!\\d)(?:\\+?1[\\s.-]?)?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}(?!\\d)"),
                PiiEntityType.PHONE_NUMBER, "phone_us", 0.9));

        // International phone (E.164). Never inside a longer run of digits, for the same reason.
        patterns.add(new PatternEntry(
                Pattern.compile("(?<!\\d)\\+[1-9]\\d{6,14}(?!\\d)"),
                PiiEntityType.PHONE_NUMBER, "phone_intl", 0.85));

        // SSN (reject 000/666/9xx area numbers per SSA rules)
        patterns.add(new PatternEntry(
                Pattern.compile("(?!000|666|9\\d{2})\\d{3}[\\s-]?(?!00)\\d{2}[\\s-]?(?!0000)\\d{4}"),
                PiiEntityType.SSN, "ssn", 0.9));

        // Credit card (Visa, MC, Amex, Discover — Luhn post-validated)
        patterns.add(new PatternEntry(
                Pattern.compile("(?:4\\d{3}|5[1-5]\\d{2}|3[47]\\d{2}|6(?:011|5\\d{2}))[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{1,4}"),
                PiiEntityType.CREDIT_CARD, "credit_card", 0.95));

        // Date of birth (MM/DD/YYYY or MM-DD-YYYY)
        patterns.add(new PatternEntry(
                Pattern.compile("(?:0[1-9]|1[0-2])[/\\-](?:0[1-9]|[12]\\d|3[01])[/\\-](?:19|20)\\d{2}"),
                PiiEntityType.DATE_OF_BIRTH, "dob", 0.8));

        // IPv4 address. Not part of a longer dotted run such as a five-part version string: no digit or dot
        // before it, and no further digit or ".<digit>" after it. A four-part version string still matches,
        // because nothing in its shape tells it apart from an address.
        patterns.add(new PatternEntry(
                Pattern.compile("(?<![\\d.])(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)(?!\\.?\\d)"),
                PiiEntityType.IP_ADDRESS, "ipv4", 0.85));

        // US passport number (6-9 alphanumeric characters)
        patterns.add(new PatternEntry(
                Pattern.compile("(?i)(?:passport[\\s#:]*)[A-Z0-9]{6,9}"),
                PiiEntityType.PASSPORT_NUMBER, "passport_us", 0.8));

        // Driver's license (generic: state letter + digits, requires keyword context)
        patterns.add(new PatternEntry(
                Pattern.compile("(?i)(?:driver'?s?\\s*(?:license|licence|lic)[\\s#:]*)[A-Z0-9]{5,15}"),
                PiiEntityType.DRIVERS_LICENSE, "drivers_license", 0.75));

        // IBAN (international bank account number)
        patterns.add(new PatternEntry(
                Pattern.compile("[A-Z]{2}\\d{2}[\\s]?[A-Z0-9]{4}(?:[\\s]?[A-Z0-9]{4}){2,7}(?:[\\s]?[A-Z0-9]{1,4})?"),
                PiiEntityType.IBAN, "iban", 0.9));

        // Medical Record Number (keyword + digits)
        patterns.add(new PatternEntry(
                Pattern.compile("(?i)(?:MRN|medical\\s*record)[\\s#:]*\\d{4,12}"),
                PiiEntityType.MEDICAL_RECORD_NUMBER, "mrn", 0.85));

        // DEA number (checksum post-validated)
        patterns.add(new PatternEntry(
                Pattern.compile("[A-Za-z]{2}\\d{7}"),
                PiiEntityType.DEA_NUMBER, "dea", 0.9));

        // NPI number (keyword context required, Luhn post-validated)
        patterns.add(new PatternEntry(
                Pattern.compile("(?i)(?:NPI)[\\s#:]*\\d{10}"),
                PiiEntityType.NPI_NUMBER, "npi", 0.9));

        // Person name (salutation heuristic)
        patterns.add(new PatternEntry(
                Pattern.compile("(?:Mr\\.?|Mrs\\.?|Ms\\.?|Dr\\.?|Prof\\.?)\\s+[A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,2}"),
                PiiEntityType.PERSON_NAME, "person_name", 0.7));

        // India Aadhaar — 12 digits, optional spaces (Verhoeff post-validated).
        // Confidence 0.95 (> loose phone 0.9) so a Verhoeff-validated match wins the
        // overlap dedup when a contiguous 12-digit Aadhaar also matches the phone shape.
        patterns.add(new PatternEntry(
                Pattern.compile("\\b\\d{4}\\s?\\d{4}\\s?\\d{4}\\b"),
                PiiEntityType.AADHAAR, "aadhaar", 0.95));

        // India PAN — AAAAA9999A (format post-validated)
        patterns.add(new PatternEntry(
                Pattern.compile("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b"),
                PiiEntityType.PAN, "pan", 0.95));

        return patterns;
    }
}