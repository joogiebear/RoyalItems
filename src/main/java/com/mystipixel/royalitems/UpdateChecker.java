package com.mystipixel.royalitems;

import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the latest GitHub release against the running version, off the main thread, and logs one line
 * if a newer one exists. Everything fails quietly: no release, a private repo, a rate-limit, or a
 * network hiccup all just skip the notice — an update check is never worth an error in the console.
 */
public final class UpdateChecker {

    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern HTML_URL = Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]+)\"");

    private final Plugin plugin;
    private final String repo;   // "owner/name"

    public UpdateChecker(Plugin plugin, String repo) {
        this.plugin = plugin;
        this.repo = repo;
    }

    public void check() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::fetch);
    }

    private void fetch() {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(
                    "https://api.github.com/repos/" + repo + "/releases/latest").toURL().openConnection();
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "RoyalItems");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() != 200) {
                return;   // no release, private, or rate-limited — stay quiet
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
            Matcher tag = TAG.matcher(body);
            if (!tag.find()) {
                return;
            }
            String current = plugin.getPluginMeta().getVersion();
            if (isNewer(tag.group(1), current)) {
                Matcher url = HTML_URL.matcher(body);
                String link = url.find() ? url.group(1) : "https://github.com/" + repo + "/releases";
                plugin.getLogger().info("A new version is available: " + strip(tag.group(1))
                        + " (you have " + current + ") — " + link);
            }
        } catch (Exception e) {
            // a failed update check is never a problem worth reporting
        }
    }

    /** True if release version {@code latest} is strictly newer than {@code current} (numeric, dotted). */
    static boolean isNewer(String latest, String current) {
        int[] a = parse(latest);
        int[] b = parse(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int[] parse(String version) {
        String[] parts = strip(version).split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("\\D", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static String strip(String version) {
        return version.isEmpty() || (version.charAt(0) != 'v' && version.charAt(0) != 'V')
                ? version : version.substring(1);
    }
}
