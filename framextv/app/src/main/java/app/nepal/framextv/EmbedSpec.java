package app.nepal.framextv;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Fixed documented embed routes; no incoming URL, script or provider is accepted. */
final class EmbedSpec {
    final String mediaType, title, path;
    final int tmdbId, season, episode;

    EmbedSpec(String mediaType, int tmdbId, int season, int episode, String title) {
        if (!("movie".equals(mediaType) || "tv".equals(mediaType)) || tmdbId <= 0) {
            throw new IllegalArgumentException("Choose a movie or episode first.");
        }
        if ("tv".equals(mediaType) && (season < 0 || season > 1000 || episode < 1 || episode > 10000)) {
            throw new IllegalArgumentException("Choose a valid season and episode.");
        }
        this.mediaType = mediaType;
        this.tmdbId = tmdbId;
        this.season = season;
        this.episode = episode;
        this.title = sanitizeTitle(title);
        this.path = "/embed/" + tmdbId + ("tv".equals(mediaType) ? "/" + season + "/" + episode : "");
    }

    static String sanitizeTitle(String title) {
        if (title == null) return "Theater";
        StringBuilder clean = new StringBuilder(Math.min(title.length(), 160));
        for (int index = 0; index < title.length() && clean.length() < 160; index++) {
            char character = title.charAt(index);
            clean.append(Character.isISOControl(character) ? ' ' : character);
        }
        String result = clean.toString().trim();
        return result.isEmpty() ? "Theater" : result;
    }

    String address() {
        // Theater owns the pending start so a user's Pause can cancel it during source loading.
        return "https://framextv.xyz" + path + "?autoplay=0&muted=0&controls=1";
    }

    String originFor(String address) {
        if (address == null || address.length() > 4096) return null;
        try {
            URI uri = new URI(address);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !(path.equals(uri.getRawPath()) || (path + "/").equals(uri.getRawPath()))) return null;
            host = host.toLowerCase(Locale.ROOT);
            return "framextv.cc".equals(host) || "framextv.xyz".equals(host) ? "https://" + host : null;
        } catch (URISyntaxException invalid) {
            return null;
        }
    }
}
