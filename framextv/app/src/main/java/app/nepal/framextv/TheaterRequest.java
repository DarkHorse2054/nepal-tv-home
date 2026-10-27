package app.nepal.framextv;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small local title-command parser; it does not call a language model. */
final class TheaterRequest {
    private static final Pattern POLITE = Pattern.compile("(?iu)^(?:please\\s+|(?:can|could|would)\\s+you\\s+|i\\s+(?:want|would\\s+like)\\s+to\\s+|i'd\\s+like\\s+to\\s+)");
    private static final Pattern VERB = Pattern.compile("(?iu)^(play|watch|search(?:\\s+for)?|find)\\s+(.+)$");
    private static final Pattern EPISODE = Pattern.compile("(?iu)\\s+(?:season\\s*([0-9]+)\\s*[,;:-]?\\s*episode\\s*([0-9]+)|s([0-9]+)\\s*e([0-9]+))\\s*$");
    private static final Pattern PARTIAL_EPISODE = Pattern.compile("(?iu)\\s+(?:season\\s*[0-9]+|episode\\s*[0-9]+|s[0-9]+|e[0-9]+)\\s*$");
    private static final Pattern YEAR = Pattern.compile("\\s*\\(([0-9]{4})\\)\\s*$");
    final String title, year;
    final boolean play;
    final int season, episode;

    private TheaterRequest(String title, String year, boolean play, int season, int episode) {
        this.title = title;
        this.year = year;
        this.play = play;
        this.season = season;
        this.episode = episode;
    }

    /** Canonical unpadded base64url, bounded before decoding; API-25 compatible. */
    static TheaterRequest decode(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > 856 || encoded.length() % 4 == 1)
            throw invalid();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(640);
        int bits = 0, pending = 0;
        for (int index = 0; index < encoded.length(); index++) {
            char c = encoded.charAt(index);
            int value = c >= 'A' && c <= 'Z' ? c - 'A' : c >= 'a' && c <= 'z' ? c - 'a' + 26
                    : c >= '0' && c <= '9' ? c - '0' + 52 : c == '-' ? 62 : c == '_' ? 63 : -1;
            if (value < 0) throw invalid();
            pending = (pending << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                bytes.write((pending >>> bits) & 255);
                pending &= (1 << bits) - 1;
                if (bytes.size() > 640) throw invalid();
            }
        }
        if (pending != 0) throw invalid();
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            return parse(value);
        } catch (CharacterCodingException invalid) {
            throw invalid();
        }
    }

    static TheaterRequest parse(String raw) {
        if (raw == null || raw.codePointCount(0, raw.length()) > 160) throw invalid();
        for (int index = 0; index < raw.length();) {
            char first = raw.charAt(index);
            if (Character.isSurrogate(first) && (!Character.isHighSurrogate(first)
                    || index + 1 >= raw.length() || !Character.isLowSurrogate(raw.charAt(index + 1)))) throw invalid();
            int point = raw.codePointAt(index);
            if (Character.isISOControl(point)) throw invalid();
            index += Character.charCount(point);
        }
        String value = raw.trim();
        for (int count = 0; count < 4; count++) {
            Matcher polite = POLITE.matcher(value);
            if (!polite.find()) break;
            value = value.substring(polite.end()).trim();
        }
        Matcher verb = VERB.matcher(value);
        boolean play = false;
        if (verb.matches()) {
            play = "play".equalsIgnoreCase(verb.group(1)) || "watch".equalsIgnoreCase(verb.group(1));
            value = verb.group(2).trim();
        }
        int season = -1, episode = -1;
        Matcher episodeMatch = EPISODE.matcher(value);
        if (episodeMatch.find()) {
            season = boundedNumber(episodeMatch.group(1) != null ? episodeMatch.group(1) : episodeMatch.group(3), 0, 1000);
            episode = boundedNumber(episodeMatch.group(2) != null ? episodeMatch.group(2) : episodeMatch.group(4), 1, 10000);
            value = value.substring(0, episodeMatch.start()).trim();
        } else if (PARTIAL_EPISODE.matcher(value).find()) {
            throw new IllegalArgumentException("Include both a season and an episode.");
        }
        String year = "";
        Matcher yearMatch = YEAR.matcher(value);
        if (yearMatch.find()) {
            year = yearMatch.group(1);
            value = value.substring(0, yearMatch.start()).trim();
        }
        if (value.length() >= 2) {
            char first = value.charAt(0), last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')
                    || (first == '“' && last == '”') || (first == '‘' && last == '’'))
                value = value.substring(1, value.length() - 1).trim();
        }
        // Match the existing catalog's query bound without truncating a title.
        if (value.isEmpty() || value.length() > 120) throw invalid();
        return new TheaterRequest(value, year, play, season, episode);
    }

    boolean hasEpisode() { return season >= 0 && episode >= 1; }

    boolean matches(String candidateTitle, String candidateYear, String type) {
        return ("movie".equals(type) || "tv".equals(type)) && (!hasEpisode() || "tv".equals(type))
                && (year.isEmpty() || year.equals(candidateYear))
                && normalize(title).equals(normalize(candidateTitle));
    }

    int uniqueMatch(String[] titles, String[] years, String[] types) {
        if(titles==null||years==null||types==null||titles.length!=years.length||titles.length!=types.length)
            throw new IllegalArgumentException("Invalid title candidates.");
        int match=-1;
        for(int index=0;index<titles.length;index++)if(matches(titles[index],years[index],types[index])){
            if(match>=0)return -1;
            match=index;
        }
        return match;
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        String value = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder clean = new StringBuilder(value.length());
        boolean space = false;
        for (int index = 0; index < value.length();) {
            int point = value.codePointAt(index);
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) space = clean.length() > 0;
            else {
                if (space) clean.append(' ');
                clean.appendCodePoint(point);
                space = false;
            }
            index += Character.charCount(point);
        }
        return clean.toString();
    }

    private static int boundedNumber(String value, int minimum, int maximum) {
        try {
            int result = Integer.parseInt(value);
            if (result >= minimum && result <= maximum) return result;
        } catch (NumberFormatException invalid) { /* Reject, rather than guessing another episode. */ }
        throw new IllegalArgumentException("Choose an available season and episode.");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Use a title, optionally with a year, season and episode.");
    }
}
