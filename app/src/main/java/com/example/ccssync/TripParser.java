package com.example.ccssync;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the words (with positions) that on-device text recognition found in a
 * screenshot of a CCS trip ("pairing") details page, and turns them into a Trip:
 * pairing code, duty periods with report times, and flights with local times.
 *
 * CCS shows every time in the local time of the airport it belongs to, so each
 * time is attached to that airport's time zone.
 *
 * Plain Java with no Android imports so it can be tested on a desktop JVM.
 */
public final class TripParser {

    // ---------------------------------------------------------------- input

    /** One recognized word and its bounding box in screenshot pixels. */
    public static final class Word {
        public final String text;
        public final int left, top, right, bottom;

        public Word(String text, int left, int top, int right, int bottom) {
            this.text = text;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        double cx() { return (left + right) / 2.0; }
        double cy() { return (top + bottom) / 2.0; }
        int h() { return Math.max(1, bottom - top); }

        @Override public String toString() { return text + "@" + left + "," + top; }
    }

    /** Looks up an airport code's time zone. */
    public interface Airports {
        /** Time zone ID (e.g. "America/Chicago") or null if the code is unknown. */
        String zoneFor(String code);
    }

    // --------------------------------------------------------------- output

    public static final class Leg {
        public String flight;        // "UA 1434"
        public String from, to;      // "SFO", "LAS"
        public ZonedDateTime dep, arr;

        @Override public String toString() {
            return flight + "  " + from + " " + HM.format(dep) + " -> " + to + " " + HM.format(arr)
                + "  (" + dep.toLocalDate() + " " + dep.getZone() + " -> " + arr.toLocalDate() + " " + arr.getZone() + ")";
        }
    }

    public static final class Duty {
        public int number;           // 1-based
        public ZonedDateTime report;
        public final List<Leg> legs = new ArrayList<>();

        /** Release: CCS counts layovers from 15 minutes after the last arrival. */
        public ZonedDateTime end() {
            return legs.isEmpty() ? report : legs.get(legs.size() - 1).arr.plusMinutes(DEBRIEF_MINUTES);
        }
    }

    public static final class Trip {
        public String pairing;       // "F1399"
        public LocalDate startDate;  // first day of the trip
        public final List<Duty> duties = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();

        /** Identifies this trip in the calendar, so a newer screenshot replaces it. */
        public String key() { return pairing + ":" + startDate; }
    }

    static final int DEBRIEF_MINUTES = 15;

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.US);

    // -------------------------------------------------------------- parsing

    private static final Pattern TIME = Pattern.compile("^(\\d{1,2})[:.](\\d{2})$");
    private static final Pattern DATE = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{2,4})");
    private static final Pattern HEADER = Pattern.compile(
        "\\b([A-Z0-9]{4,8})\\s*[-–—]\\s*(\\d{1,2})/(\\d{1,2})/(\\d{2,4})");
    private static final Pattern FLIGHT_JOINED = Pattern.compile("^UA(\\d{1,4})$");
    private static final Pattern DIGITS = Pattern.compile("^\\d{1,4}$");
    private static final Pattern DAY = Pattern.compile("^\\d{1,2}$");

    private static final class Row {
        final List<Word> words = new ArrayList<>();
        double cy;
        int top = Integer.MAX_VALUE, bottom = Integer.MIN_VALUE;

        void add(Word w) {
            words.add(w);
            cy = (cy * (words.size() - 1) + w.cy()) / words.size();
            top = Math.min(top, w.top);
            bottom = Math.max(bottom, w.bottom);
        }

        String joined() {
            StringBuilder sb = new StringBuilder();
            for (Word w : words) sb.append(w.text).append(' ');
            return sb.toString().trim();
        }
    }

    private static final class RawLeg {
        String flight;
        String depTime, arrTime;
        String fromRaw, toRaw;
        Integer dayOfMonth;
        double y;
    }

    private static final class RawDuty {
        double y;
        LocalTime report;
        LocalDate date;
        Duration layoverBefore; // "Layover 15:45" shown just above this duty
        final List<RawLeg> legs = new ArrayList<>();
    }

    /**
     * @param words      every word the text recognizer found, any order
     * @param imageWidth screenshot width in pixels (column positions are relative to it)
     * @return the trip, or null if this doesn't look like a CCS trip page
     */
    public static Trip parse(List<Word> words, int imageWidth, Airports airports) {
        if (words == null || words.isEmpty()) return null;
        double W = imageWidth;
        List<Row> rows = groupRows(words);

        Trip trip = new Trip();

        // Header "F1399 - 11/06/26": pairing code and start date.
        for (Row r : rows) {
            Matcher m = HEADER.matcher(r.joined().toUpperCase(Locale.US));
            if (m.find()) {
                String code = m.group(1);
                if (code.matches("^[A-Z].*") && !code.startsWith("UA")) {
                    trip.pairing = code;
                    trip.startDate = toDate(m.group(2), m.group(3), m.group(4));
                    break;
                }
            }
        }

        // Walk rows top to bottom: "Report" rows start duty periods, "UA ####" rows are flights.
        List<RawDuty> duties = new ArrayList<>();
        Duration pendingLayover = null;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            String joinedUpper = r.joined().toUpperCase(Locale.US);

            if (joinedUpper.contains("LAYOVER")) {
                Matcher lm = Pattern.compile("LAYOVER\\D*(\\d{1,3}):(\\d{2})").matcher(joinedUpper);
                if (lm.find()) {
                    pendingLayover = Duration.ofHours(Integer.parseInt(lm.group(1)))
                        .plusMinutes(Integer.parseInt(lm.group(2)));
                }
                continue;
            }

            boolean isDpHeader = joinedUpper.matches(".*\\bDP\\s*\\d.*");
            boolean hasReport = joinedUpper.contains("REPORT");
            if (isDpHeader || hasReport) {
                RawDuty d = lastDutyNear(duties, r.cy, r.bottom - r.top);
                if (d == null) {
                    d = new RawDuty();
                    d.y = r.cy;
                    d.layoverBefore = pendingLayover;
                    pendingLayover = null;
                    duties.add(d);
                }
                if (hasReport) {
                    for (Word w : r.words) {
                        LocalTime t = time(w.text);
                        if (t != null && w.cx() > W * 0.5) d.report = t;
                    }
                }
                if (isDpHeader && d.date == null) {
                    Matcher m = DATE.matcher(r.joined());
                    if (m.find()) d.date = toDate(m.group(1), m.group(2), m.group(3));
                }
                continue;
            }

            List<Word> times = new ArrayList<>();
            for (Word w : r.words) {
                if (time(w.text) != null && w.cx() > W * 0.25 && w.cx() < W * 0.63) times.add(w);
            }
            times.sort(Comparator.comparingDouble(Word::cx));
            String flight = flightNumber(r.words);
            // Segments: a UA flight, or any row under a duty header with a departure
            // and arrival time (deadheads, ground transport like "H FAKE").
            if (flight == null) {
                if (duties.isEmpty() || times.size() < 2) continue;
                flight = segmentLabel(r.words, W);
                if (flight == null) continue;
            }

            RawLeg leg = new RawLeg();
            leg.flight = flight;
            leg.y = r.cy;
            if (times.size() >= 2) {
                leg.depTime = times.get(0).text;
                leg.arrTime = times.get(1).text;
            }

            // Airport codes sit on the line under the times.
            List<Word> codes = new ArrayList<>();
            for (int j = i + 1; j < rows.size() && j <= i + 2; j++) {
                Row below = rows.get(j);
                if (flightNumber(below.words) != null || countMidTimes(below.words, W) >= 2) break;
                for (Word w : below.words) {
                    if (w.cx() > W * 0.28 && w.cx() < W * 0.66 && airportish(w.text) != null) codes.add(w);
                }
                if (codes.size() >= 2) break;
            }
            codes.sort(Comparator.comparingDouble(Word::cx));
            if (codes.size() >= 2) {
                leg.fromRaw = airportish(codes.get(0).text);
                leg.toRaw = airportish(codes.get(1).text);
            }

            // Day-of-month badge at the far left ("06").
            double h = r.bottom - r.top;
            Word best = null;
            for (Word w : words) {
                if (w.cx() < W * 0.12 && DAY.matcher(clean(w.text)).matches()
                    && w.cy() > r.cy - 1.5 * h && w.cy() < r.cy + 3 * h) {
                    if (best == null || Math.abs(w.cy() - r.cy) < Math.abs(best.cy() - r.cy)) best = w;
                }
            }
            if (best != null) {
                int day = Integer.parseInt(clean(best.text));
                if (day >= 1 && day <= 31) leg.dayOfMonth = day;
            }

            if (duties.isEmpty()) {
                RawDuty d = new RawDuty();
                d.y = r.cy;
                duties.add(d);
            }
            duties.get(duties.size() - 1).legs.add(leg);
        }

        duties.removeIf(d -> d.legs.isEmpty());
        if (duties.isEmpty()) return null;

        if (trip.startDate == null) trip.startDate = duties.get(0).date;
        if (trip.startDate == null) return null;
        if (trip.pairing == null) {
            trip.pairing = "TRIP";
            trip.warnings.add("Couldn't read the trip code");
        }

        // Fix misread airport codes, then check flights connect.
        List<RawLeg> allLegs = new ArrayList<>();
        for (RawDuty d : duties) allLegs.addAll(d.legs);
        String[] from = new String[allLegs.size()];
        String[] to = new String[allLegs.size()];
        boolean[] fromSure = new boolean[allLegs.size()];
        boolean[] toSure = new boolean[allLegs.size()];
        for (int k = 0; k < allLegs.size(); k++) {
            RawLeg l = allLegs.get(k);
            from[k] = resolveAirport(l.fromRaw, airports);
            to[k] = resolveAirport(l.toRaw, airports);
            fromSure[k] = l.fromRaw != null && PREFERRED.contains(l.fromRaw);
            toSure[k] = l.toRaw != null && PREFERRED.contains(l.toRaw);
        }
        for (int k = 0; k + 1 < allLegs.size(); k++) {
            if (to[k] != null && to[k].equals(from[k + 1])) continue;
            if (toSure[k] && !fromSure[k + 1]) from[k + 1] = to[k];
            else if (fromSure[k + 1] && !toSure[k]) to[k] = from[k + 1];
            else if (to[k] == null) to[k] = from[k + 1];
            else if (from[k + 1] == null) from[k + 1] = to[k];
        }

        // Trips start and end at the crew base. If the first departure isn't a
        // United station but the last arrival is, the first one was misread.
        int lastIdx = allLegs.size() - 1;
        if (lastIdx > 0 && to[lastIdx] != null && PREFERRED.contains(to[lastIdx])
            && from[0] != null && !PREFERRED.contains(from[0])) {
            trip.warnings.add("Read first airport as " + from[0] + ", assumed base " + to[lastIdx]);
            from[0] = to[lastIdx];
        }

        // Turn local clock times into real moments.
        ZonedDateTime prevEnd = null;
        int legIndex = 0;
        for (int di = 0; di < duties.size(); di++) {
            RawDuty rd = duties.get(di);
            Duty duty = new Duty();
            duty.number = di + 1;

            RawLeg first = rd.legs.get(0);
            String firstFrom = from[legIndex];
            ZoneId firstZone = zone(firstFrom, airports, trip);
            LocalTime firstDep = time(first.depTime);

            LocalDate date = rd.date;
            if (date == null && first.dayOfMonth != null) date = dayOnOrAfter(trip.startDate, first.dayOfMonth);
            if (date == null && di == 0) date = trip.startDate;

            LocalTime reportTime = rd.report;
            if (reportTime == null && rd.layoverBefore != null && prevEnd != null) {
                // Report = previous release + the layover CCS shows (exact, even when
                // the report time itself is covered on screen).
                reportTime = prevEnd.plus(rd.layoverBefore).withZoneSameInstant(firstZone).toLocalTime();
            }
            if (reportTime == null && firstDep != null) {
                reportTime = firstDep.minusMinutes(60);
                trip.warnings.add("Duty " + duty.number + ": no report time found, using 1 hour before departure");
            }
            if (reportTime == null) return null;

            if (date == null) {
                // Not on screen: first day after the previous duty that leaves real rest (8h+).
                LocalDate cand = prevEnd.withZoneSameInstant(firstZone).toLocalDate();
                while (ZonedDateTime.of(cand, reportTime, firstZone).isBefore(prevEnd.plusHours(8))) {
                    cand = cand.plusDays(1);
                }
                date = cand;
            }
            duty.report = ZonedDateTime.of(date, reportTime, firstZone);

            ZonedDateTime prevArr = null;
            for (RawLeg rl : rd.legs) {
                Leg leg = new Leg();
                leg.flight = rl.flight;
                leg.from = from[legIndex];
                leg.to = to[legIndex];
                legIndex++;

                LocalTime dep = time(rl.depTime);
                LocalTime arr = time(rl.arrTime);
                if (dep == null || arr == null || leg.from == null || leg.to == null) {
                    trip.warnings.add("Skipped " + rl.flight + ": couldn't read its times or airports");
                    continue;
                }
                ZoneId zFrom = zone(leg.from, airports, trip);
                ZoneId zTo = zone(leg.to, airports, trip);

                LocalDate depDate;
                if (rl.dayOfMonth != null) depDate = dayOnOrAfter(trip.startDate, rl.dayOfMonth);
                else if (prevArr != null) depDate = prevArr.withZoneSameInstant(zFrom).toLocalDate();
                else depDate = duty.report.withZoneSameInstant(zFrom).toLocalDate();

                ZonedDateTime depZ = ZonedDateTime.of(depDate, dep, zFrom);
                ZonedDateTime floor = prevArr != null ? prevArr : duty.report;
                if (rl.dayOfMonth == null) {
                    while (depZ.isBefore(floor)) depZ = depZ.plusDays(1);
                }

                ZonedDateTime arrZ = ZonedDateTime.of(depZ.withZoneSameInstant(zTo).toLocalDate(), arr, zTo);
                while (arrZ.isBefore(depZ)) arrZ = arrZ.plusDays(1);

                leg.dep = depZ;
                leg.arr = arrZ;
                duty.legs.add(leg);
                prevArr = arrZ;
            }
            if (!duty.legs.isEmpty()) {
                // A report shown after the first departure means it was the evening before.
                if (duty.report.isAfter(duty.legs.get(0).dep)) duty.report = duty.report.minusDays(1);
                trip.duties.add(duty);
                prevEnd = duty.end();
            }
        }
        return trip.duties.isEmpty() ? null : trip;
    }

    // ----------------------------------------------------------- helpers

    private static List<Row> groupRows(List<Word> words) {
        List<Word> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(Word::cy));
        List<Integer> heights = new ArrayList<>();
        for (Word w : sorted) heights.add(w.h());
        Collections.sort(heights);
        double medianH = heights.get(heights.size() / 2);

        List<Row> rows = new ArrayList<>();
        Row cur = null;
        for (Word w : sorted) {
            if (cur == null || Math.abs(w.cy() - cur.cy) > medianH * 0.55) {
                cur = new Row();
                rows.add(cur);
            }
            cur.add(w);
        }
        for (Row r : rows) r.words.sort(Comparator.comparingDouble(Word::cx));
        return rows;
    }

    private static RawDuty lastDutyNear(List<RawDuty> duties, double y, double rowH) {
        if (duties.isEmpty()) return null;
        RawDuty d = duties.get(duties.size() - 1);
        // "DP 01 ... Report 15:50" can split into two rows; treat them as one header.
        return d.legs.isEmpty() && Math.abs(d.y - y) < Math.max(rowH, 10) * 2.5 ? d : null;
    }

    private static int countMidTimes(List<Word> rowWords, double W) {
        int n = 0;
        for (Word w : rowWords) if (time(w.text) != null && w.cx() > W * 0.25 && w.cx() < W * 0.63) n++;
        return n;
    }

    /** Left-column label of a non-UA segment, e.g. "H FAKE" (day badge excluded). */
    private static String segmentLabel(List<Word> rowWords, double W) {
        StringBuilder sb = new StringBuilder();
        for (Word w : rowWords) {
            if (w.cx() < W * 0.12 || w.cx() > W * 0.32) continue;
            String t = clean(w.text);
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
        }
        return sb.length() == 0 ? "Segment" : sb.toString();
    }

    private static String flightNumber(List<Word> rowWords) {
        for (int i = 0; i < rowWords.size(); i++) {
            String t = clean(rowWords.get(i).text).toUpperCase(Locale.US);
            Matcher m = FLIGHT_JOINED.matcher(t);
            if (m.matches()) return "UA " + m.group(1);
            if (t.equals("UA") && i + 1 < rowWords.size()) {
                String n = clean(rowWords.get(i + 1).text);
                if (DIGITS.matcher(n).matches()) return "UA " + n;
            }
        }
        return null;
    }

    static LocalTime time(String s) {
        if (s == null) return null;
        Matcher m = TIME.matcher(clean(s));
        if (!m.matches()) return null;
        int h = Integer.parseInt(m.group(1)), min = Integer.parseInt(m.group(2));
        if (h > 23 || min > 59) return null;
        return LocalTime.of(h, min);
    }

    private static String clean(String s) {
        return s.trim().replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "");
    }

    /** A token that could be a 3-letter airport code (allowing for common misreads). */
    static String airportish(String s) {
        String t = clean(s).toUpperCase(Locale.US);
        if (t.length() != 3) return null;
        if (t.equals("HOL") || t.equals("UA")) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : t.toCharArray()) {
            switch (c) {
                case '0': sb.append('O'); break;
                case '5': sb.append('S'); break;
                case '1': sb.append('I'); break;
                case '8': sb.append('B'); break;
                case '6': sb.append('G'); break;
                case '2': sb.append('Z'); break;
                default:
                    if (c < 'A' || c > 'Z') return null;
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    // Letters text recognition commonly mixes up.
    private static final String[][] CONFUSIONS = {
        {"E", "F"}, {"F", "E"}, {"Q", "O"}, {"O", "Q"}, {"O", "D"}, {"D", "O"},
        {"C", "G"}, {"G", "C"}, {"I", "L"}, {"L", "I"}, {"I", "T"}, {"T", "I"},
        {"U", "V"}, {"V", "U"}, {"M", "N"}, {"N", "M"}, {"B", "R"}, {"R", "B"},
        {"P", "R"}, {"H", "N"}, {"K", "X"}, {"W", "V"},
    };

    static String resolveAirport(String raw, Airports airports) {
        if (raw == null) return null;
        if (PREFERRED.contains(raw)) return raw;
        for (int i = 0; i < 3; i++) {
            for (String[] c : CONFUSIONS) {
                if (raw.charAt(i) == c[0].charAt(0)) {
                    String v = raw.substring(0, i) + c[1] + raw.substring(i + 1);
                    if (PREFERRED.contains(v)) return v;
                }
            }
        }
        // Two misread letters (e.g. "SEQ" for SFO: E/F and Q/O).
        for (int i = 0; i < 3; i++) {
            for (String[] c1 : CONFUSIONS) {
                if (raw.charAt(i) != c1[0].charAt(0)) continue;
                String v1 = raw.substring(0, i) + c1[1] + raw.substring(i + 1);
                for (int j = i + 1; j < 3; j++) {
                    for (String[] c2 : CONFUSIONS) {
                        if (v1.charAt(j) != c2[0].charAt(0)) continue;
                        String v2 = v1.substring(0, j) + c2[1] + v1.substring(j + 1);
                        if (PREFERRED.contains(v2)) return v2;
                    }
                }
            }
        }
        if (airports.zoneFor(raw) != null) return raw;
        return null;
    }

    private static ZoneId zone(String code, Airports airports, Trip trip) {
        String z = code == null ? null : airports.zoneFor(code);
        if (z == null) {
            String msg = "Unknown airport " + code + ": used the phone's time zone";
            if (!trip.warnings.contains(msg)) trip.warnings.add(msg);
            return ZoneId.systemDefault();
        }
        return ZoneId.of(z);
    }

    private static LocalDate toDate(String mm, String dd, String yy) {
        int y = Integer.parseInt(yy);
        if (y < 100) y += 2000;
        try {
            return LocalDate.of(y, Integer.parseInt(mm), Integer.parseInt(dd));
        } catch (Exception e) {
            return null;
        }
    }

    /** The first date on or after `start` with the given day of month (trips cross month ends). */
    private static LocalDate dayOnOrAfter(LocalDate start, int day) {
        LocalDate d = start;
        for (int i = 0; i < 45; i++, d = d.plusDays(1)) {
            if (d.getDayOfMonth() == day) return d;
        }
        return null;
    }

    // ------------------------------------------------- calendar formatting

    /** One calendar event per duty day. */
    public static final class EventSpec {
        public String title, description, location;
        public ZonedDateTime start, end;
    }

    public static List<EventSpec> toEvents(Trip trip) {
        List<EventSpec> out = new ArrayList<>();
        for (int i = 0; i < trip.duties.size(); i++) {
            Duty d = trip.duties.get(i);
            EventSpec e = new EventSpec();
            StringBuilder route = new StringBuilder(d.legs.get(0).from);
            for (Leg l : d.legs) route.append('-').append(l.to);
            // Google Calendar shows events in the phone's time zone, so put the local
            // report time (as CCS shows it) in the title.
            e.title = "✈ " + trip.pairing + " · Rpt " + HM.format(d.report) + " · " + route;
            e.location = d.legs.get(0).from;
            e.start = d.report;
            e.end = d.end();

            StringBuilder sb = new StringBuilder();
            sb.append("Duty ").append(d.number).append(" of ").append(trip.duties.size())
              .append(" · Report ").append(HM.format(d.report)).append(' ').append(d.legs.get(0).from).append('\n');
            for (Leg l : d.legs) {
                sb.append(l.flight).append("  ").append(l.from).append(' ').append(HM.format(l.dep))
                  .append(" → ").append(l.to).append(' ').append(HM.format(l.arr)).append('\n');
            }
            sb.append("Release ").append(HM.format(d.end())).append(' ')
              .append(d.legs.get(d.legs.size() - 1).to).append(" (15 min after arrival)\n");
            if (i + 1 < trip.duties.size()) {
                Leg last = d.legs.get(d.legs.size() - 1);
                Duration rest = Duration.between(d.end(), trip.duties.get(i + 1).report);
                sb.append("Layover ").append(last.to).append(" · ")
                  .append(rest.toHours()).append('h').append(String.format(Locale.US, "%02d", rest.toMinutesPart()))
                  .append(" layover\n");
            }
            sb.append("(Times are local to each airport.)\n");
            out.add(e);
            e.description = sb.toString();
        }
        return out;
    }

    // United domestic network and common international stations. Used to correct
    // misread codes (e.g. "SEO" -> "SFO") toward airports United actually flies.
    static final Set<String> PREFERRED = new HashSet<>(Arrays.asList(
        "SFO", "DEN", "ORD", "IAH", "EWR", "IAD", "LAX", "GUM", "LAS", "SEA", "PDX", "SAN", "SJC",
        "OAK", "SMF", "SNA", "ONT", "BUR", "PSP", "SBA", "FAT", "RNO", "PHX", "TUS", "ABQ", "ELP",
        "SLC", "BOI", "ANC", "FAI", "HNL", "OGG", "KOA", "LIH", "ITO", "MSP", "DTW", "STL", "MCI",
        "OMA", "DSM", "MKE", "MDW", "IND", "CMH", "CLE", "CVG", "DAY", "PIT", "BUF", "ROC", "SYR",
        "ALB", "BDL", "PVD", "BOS", "MHT", "PWM", "BTV", "LGA", "JFK", "HPN", "ISP", "PHL", "BWI",
        "DCA", "RIC", "ORF", "RDU", "CLT", "GSO", "GSP", "CHS", "SAV", "JAX", "MCO", "TPA", "RSW",
        "FLL", "MIA", "PBI", "SRQ", "PNS", "ATL", "BNA", "MEM", "SDF", "BHM", "MSY", "AUS", "SAT",
        "DFW", "DAL", "HOU", "OKC", "TUL", "LIT", "XNA", "ICT", "COS", "JAC", "BZN", "MSO", "HDN",
        "EGE", "ASE", "MTJ", "GJT", "BIL", "FCA", "SUN", "GEG", "ICN", "NRT", "HND", "KIX", "PVG",
        "PEK", "HKG", "TPE", "MNL", "SIN", "BKK", "SYD", "MEL", "BNE", "AKL", "PPT", "SPN", "ROR",
        "MAJ", "KWA", "TKK", "PNI", "KSA", "LHR", "MAN", "EDI", "DUB", "SNN", "CDG", "AMS", "BRU",
        "FRA", "MUC", "ZRH", "GVA", "FCO", "MXP", "VCE", "NAP", "BCN", "MAD", "LIS", "OPO", "PMI",
        "ATH", "IST", "TLV", "AMM", "DXB", "DOH", "BOM", "DEL", "JNB", "CPT", "ACC", "LOS", "NBO",
        "YYZ", "YVR", "YUL", "YYC", "YEG", "YOW", "MEX", "CUN", "SJD", "PVR", "GDL", "MTY", "MZT",
        "ZIH", "LIR", "SJO", "PTY", "GUA", "SAL", "BZE", "RTB", "SAP", "TGU", "MBJ", "KIN", "NAS",
        "PUJ", "SDQ", "STT", "STX", "SJU", "AUA", "CUR", "SXM", "BDA", "GCM", "HAV", "BOG", "UIO",
        "GYE", "LIM", "SCL", "EZE", "GRU", "GIG", "FLN", "HGH"
    ));
}
