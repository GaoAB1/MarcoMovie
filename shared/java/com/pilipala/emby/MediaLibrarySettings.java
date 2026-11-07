package com.pilipala.emby;

import java.io.IOException;
import java.net.URL;
import org.json.*;

/**
 * Pure-Java rules behind the media library settings screen. No Android types here: the standard
 * app, the API 14 Lite build and the offline checks all compile this file. Credentials never
 * enter the settings payload; only view identifiers, labels and the user's selection are stored.
 */
public final class MediaLibrarySettings {
    public static final String PREFERENCES = "emby.accounts";
    public static final String SETTINGS_PREFIX = "settings:";
    public static final String VIEWS_PREFIX = "views:";

    private MediaLibrarySettings() {}

    /* org.json writes throw a checked exception on Android but not in the offline JSON fixture. */
    private static void put(JSONObject target, String field, Object value) {
        try {
            target.put(field, value);
        } catch (Exception ignored) {
        }
    }

    private static void put(JSONArray target, Object value) {
        try {
            target.put(value);
        } catch (Exception ignored) {
        }
    }

    private static void remove(JSONObject target, String field) {
        try {
            target.remove(field);
        } catch (Exception ignored) {
        }
    }

    /** Rebuild instead of JSONArray.remove(int), which is not available on API 14. */
    private static JSONArray withoutSite(JSONArray sites, String api, String key)
            throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < sites.length(); i++) {
            JSONObject existing = sites.optJSONObject(i);
            if (existing == null || api.equals(existing.optString("api"))) continue;
            if (key.length() > 0 && key.equals(existing.optString("key"))) continue;
            out.put(existing);
        }
        return out;
    }

    /** Validate an account reference before it is used as a preference key. */
    public static String key(String api) throws IOException {
        if (api == null || !api.startsWith(EmbyLibrary.PREFIX))
            throw new IOException("Emby 来源无效");
        String value = api.substring(EmbyLibrary.PREFIX.length());
        if (!value.matches("[a-zA-Z0-9_-]+")) throw new IOException("Emby 账户编号无效");
        return value;
    }

    /** Normalize a user-entered server address without contacting the server. */
    public static String normalizeServer(String raw) throws Exception {
        return EmbyLibrary.normalize(raw);
    }

    /** Never surface the token or the /emby path in the settings list. */
    public static String describe(JSONObject session) throws Exception {
        if (session == null) return "登录状态已失效，请重新登录";
        URL url = new URL(session.getString("base"));
        String address =
                url.getProtocol()
                        + "://"
                        + url.getHost()
                        + (url.getPort() > 0 ? ":" + url.getPort() : "");
        String user = session.optString("user", "");
        return user.isEmpty() ? address : address + " · " + user;
    }

    public static JSONObject parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    public static JSONArray parseViews(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new JSONArray();
        try {
            return new JSONArray(raw);
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    /** All known view ids, in server order. */
    public static JSONArray allIds(JSONArray views) {
        JSONArray ids = new JSONArray();
        for (int i = 0; i < views.length(); i++) {
            JSONObject row = views.optJSONObject(i);
            String id = row == null ? "" : row.optString("id");
            if (id.matches("[a-zA-Z0-9_-]+")) put(ids, id);
        }
        return ids;
    }

    private static boolean contains(JSONArray ids, String value) {
        for (int i = 0; i < ids.length(); i++) if (value.equals(ids.optString(i))) return true;
        return false;
    }

    /** An absent selection means "show every view"; an empty array means "hide them all". */
    public static JSONArray visibleIds(JSONObject settings, JSONArray views) {
        JSONArray selected = settings.optJSONArray("visible");
        if (selected == null) return allIds(views);
        JSONArray ids = new JSONArray();
        JSONArray known = allIds(views);
        for (int i = 0; i < selected.length(); i++) {
            String id = selected.optString(i);
            if (id.matches("[a-zA-Z0-9_-]+") && contains(known, id)) put(ids, id);
        }
        return ids;
    }

    public static boolean visible(JSONObject settings, JSONArray views, String id) {
        return contains(visibleIds(settings, views), id);
    }

    /** Toggle one view and keep the default library consistent with the selection. */
    public static void toggle(JSONObject settings, JSONArray views, String id) {
        if (!id.matches("[a-zA-Z0-9_-]+")) return;
        JSONArray ids = visibleIds(settings, views);
        boolean present = contains(ids, id);
        JSONArray next = new JSONArray();
        for (int i = 0; i < ids.length(); i++)
            if (!id.equals(ids.optString(i))) put(next, ids.optString(i));
        if (!present) put(next, id);
        put(settings, "visible", next);
        if (!contains(next, settings.optString("default", ""))) remove(settings, "default");
    }

    public static void setDefault(JSONObject settings, JSONArray views, String id) {
        if (id.isEmpty()) remove(settings, "default");
        else if (id.matches("[a-zA-Z0-9_-]+") && visible(settings, views, id))
            put(settings, "default", id);
    }

    /** The library used for the uncategorized "all videos" row; empty means the whole server. */
    public static String defaultParent(JSONObject settings, JSONArray views) {
        String id = settings.optString("default", "");
        return id.matches("[a-zA-Z0-9_-]+") && visible(settings, views, id) ? id : "";
    }

    /** Hide deselected views from the home category strip. */
    public static JSONObject filterHome(JSONObject home, JSONObject settings) {
        JSONArray selected = settings.optJSONArray("visible");
        JSONArray classes = home == null ? null : home.optJSONArray("class");
        if (selected == null || classes == null) return home;
        JSONArray kept = new JSONArray();
        for (int i = 0; i < classes.length(); i++) {
            JSONObject row = classes.optJSONObject(i);
            if (row != null && contains(selected, row.optString("type_id"))) put(kept, row);
        }
        put(home, "class", kept);
        return home;
    }

    /** Merge an Emby source into a config document, replacing the same account or key. */
    public static String mergeSite(String raw, JSONObject site) throws Exception {
        JSONObject root =
                raw == null || raw.trim().isEmpty() ? new JSONObject() : new JSONObject(raw);
        JSONArray sites = root.optJSONArray("sites");
        if (sites == null) sites = new JSONArray();
        String api = site.getString("api"), key = site.getString("key");
        sites = withoutSite(sites, api, key);
        sites.put(site);
        root.put("sites", sites);
        return root.toString(2);
    }

    /** Remove one Emby source while keeping every other field of the config untouched. */
    public static String removeSite(String raw, String api) throws Exception {
        if (raw == null || raw.trim().isEmpty()) return "{\"sites\":[]}";
        JSONObject root = new JSONObject(raw);
        JSONArray sites = root.optJSONArray("sites");
        if (sites != null) root.put("sites", withoutSite(sites, api, ""));
        return root.toString(2);
    }
}
