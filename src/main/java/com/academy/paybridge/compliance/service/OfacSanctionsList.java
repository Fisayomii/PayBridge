package com.academy.paybridge.compliance.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory index of the OFAC SDN list (primary names and aliases) with a forgiving name matcher.
 *
 * <p>Matching, designed to over-match rather than miss (every hit goes to a human, it never auto-blocks):
 * <ul>
 *   <li>names are normalised: accents and punctuation removed, case and word order ignored;</li>
 *   <li>filler words (BIN, AL, ABU, DE, LTD, ...) do not count as evidence;</li>
 *   <li>a word matches exactly, or within one letter when both are 5+ letters (OSAMA ~ USAMA, LADEN ~ LADIN);</li>
 *   <li>it is a hit when at least two meaningful words match and every meaningful word of the screened
 *       name appears in the listed name (so "Usama bin Ladin" hits "BIN LADIN, Usama bin Muhammad bin Awad").</li>
 * </ul>
 */
public class OfacSanctionsList {

    private static final Logger log = LogManager.getLogger(OfacSanctionsList.class);
    private static final Set<String> FILLER = Set.of("BIN", "BINT", "IBN", "AL", "EL", "ABU", "ABD", "ABDUL", "DE",
            "DA", "DI", "DU", "LA", "LE", "VAN", "VON", "DER", "DEL", "THE", "OF", "AND", "MR", "MRS", "DR",
            "CO", "LTD", "LIMITED", "INC", "LLC", "SA", "COMPANY", "CORP", "CORPORATION", "GROUP");
    private static final Pattern DOB = Pattern.compile("DOB ([^;]+)");
    static final int MAX_HITS = 5;

    private volatile Index index = Index.EMPTY;
    private volatile String lastError;

    public record Entry(int entNum, String name, String type, String program, String dob, boolean alias,
                        String[] words) {}

    public record Hit(int entNum, String listedName, String program, String type, String dob, boolean alias) {
        public String describe() {
            return "OFAC SDN possible match: " + listedName + (alias ? " (alias)" : "") + " [" + program + "]"
                    + (dob != null ? " DOB " + dob : "") + ", entry #" + entNum;
        }
    }

    public record Status(boolean loaded, int primaryNames, int aliases, Instant loadedAt, String source,
                         String lastError) {}

    private record Index(List<Entry> entries, int primary, int aliases, Instant loadedAt, String source) {
        static final Index EMPTY = new Index(List.of(), 0, 0, null, null);
    }

    // ------------------------------------------------------------------ loading

    /** Parses SDN.CSV and ALT.CSV text and swaps the index in atomically. Returns the number of entries. */
    public int load(String sdnCsv, String altCsv, String source) {
        Map<Integer, Entry> primary = new HashMap<>();
        List<Entry> entries = new ArrayList<>();
        for (List<String> row : parseCsv(new StringReader(sdnCsv))) {
            if (row.size() < 4 || !row.get(0).matches("\\d+")) continue;
            String remarks = row.size() > 11 ? row.get(11) : null;
            Entry e = entry(Integer.parseInt(row.get(0)), row.get(1), row.get(2), row.get(3), dob(remarks), false);
            primary.put(e.entNum(), e);
            entries.add(e);
        }
        int aliases = 0;
        for (List<String> row : parseCsv(new StringReader(altCsv))) {
            if (row.size() < 4 || !row.get(0).matches("\\d+")) continue;
            Entry owner = primary.get(Integer.parseInt(row.get(0)));
            if (owner == null) continue;
            entries.add(entry(owner.entNum(), row.get(3), owner.type(), owner.program(), owner.dob(), true));
            aliases++;
        }
        if (primary.isEmpty()) {
            throw new IllegalStateException("SDN list parsed to zero entries");
        }
        index = new Index(List.copyOf(entries), primary.size(), aliases, Instant.now(), source);
        lastError = null;
        log.info("OFAC SDN list loaded from {}: {} names, {} aliases", source, primary.size(), aliases);
        return entries.size();
    }

    public boolean loadFromCache(Path dir) {
        try {
            Path sdn = dir.resolve("SDN.CSV");
            Path alt = dir.resolve("ALT.CSV");
            if (!Files.exists(sdn) || !Files.exists(alt)) return false;
            load(Files.readString(sdn, StandardCharsets.UTF_8), Files.readString(alt, StandardCharsets.UTF_8),
                    "cache " + Files.getLastModifiedTime(sdn));
            return true;
        } catch (IOException | RuntimeException ex) {
            recordError("Could not read cached list: " + ex.getMessage());
            return false;
        }
    }

    public void recordError(String error) {
        lastError = error;
    }

    public boolean isLoaded() {
        return !index.entries().isEmpty();
    }

    public Status status() {
        Index i = index;
        return new Status(!i.entries().isEmpty(), i.primary(), i.aliases(), i.loadedAt(), i.source(), lastError);
    }

    // ------------------------------------------------------------------ screening

    public List<Hit> screen(String name) {
        String[] candidate = words(name);
        if (candidate.length < 2) {
            return List.of(); // a single meaningful word cannot be told apart from thousands of people
        }
        Map<Integer, Hit> hits = new java.util.LinkedHashMap<>();
        for (Entry e : index.entries()) {
            if (e.words().length < 2 || hits.containsKey(e.entNum())) continue;
            if (everyWordFound(candidate, e.words())) {
                hits.put(e.entNum(), new Hit(e.entNum(), e.name(), e.program(), e.type(), e.dob(), e.alias()));
                if (hits.size() >= MAX_HITS) break;
            }
        }
        return hits.values().stream().sorted(Comparator.comparing(Hit::alias)).toList();
    }

    private static boolean everyWordFound(String[] candidate, String[] listed) {
        for (String c : candidate) {
            boolean found = false;
            for (String l : listed) {
                if (similar(c, l)) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    static boolean similar(String a, String b) {
        if (a.equals(b)) return true;
        if (a.length() < 5 || b.length() < 5 || Math.abs(a.length() - b.length()) > 1) return false;
        return withinOneEdit(a, b);
    }

    private static boolean withinOneEdit(String a, String b) {
        if (a.length() > b.length()) { String t = a; a = b; b = t; }
        int i = 0, j = 0, edits = 0;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) == b.charAt(j)) { i++; j++; continue; }
            if (++edits > 1) return false;
            if (a.length() == b.length()) i++;
            j++;
        }
        return edits + (b.length() - j) + (a.length() - i) <= 1;
    }

    /** Meaningful, normalised words of a name. */
    static String[] words(String name) {
        if (name == null) return new String[0];
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace("'", "").toUpperCase();
        Set<String> out = new LinkedHashSet<>();
        for (String w : ascii.split("[^A-Z0-9]+")) {
            if (w.length() > 1 && !FILLER.contains(w)) out.add(w);
        }
        return out.toArray(String[]::new);
    }

    private static Entry entry(int entNum, String name, String type, String program, String dob, boolean alias) {
        return new Entry(entNum, name, blankToNull(type), blankToNull(program), dob, alias, words(name));
    }

    private static String dob(String remarks) {
        if (remarks == null) return null;
        Matcher m = DOB.matcher(remarks);
        return m.find() ? m.group(1).trim() : null;
    }

    /** OFAC writes "-0-" for an empty field. */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() || s.trim().equals("-0-") ? null : s.trim();
    }

    /** Minimal RFC 4180 reader: quoted fields, doubled quotes, CRLF, and OFAC's trailing Ctrl-Z. */
    static List<List<String>> parseCsv(Reader in) {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(in)) {
            List<String> row = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            int ch;
            while ((ch = r.read()) != -1) {
                char c = (char) ch;
                if (quoted) {
                    if (c == '"') {
                        r.mark(1);
                        int next = r.read();
                        if (next == '"') field.append('"');
                        else { quoted = false; if (next != -1) r.reset(); }
                    } else field.append(c);
                } else if (c == '"') quoted = true;
                else if (c == ',') { row.add(field.toString().trim()); field.setLength(0); }
                else if (c == '\n') { row.add(field.toString().trim()); rows.add(row); row = new ArrayList<>(); field.setLength(0); }
                else if (c != '\r' && c != '\u001a') field.append(c);
            }
            if (field.length() > 0 || !row.isEmpty()) { row.add(field.toString().trim()); rows.add(row); }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }
}
