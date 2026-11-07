package com.pilipala.marcomovie;

import com.pilipala.emby.*;
import org.json.*;

final class EmbyEngine implements SourceEngine {
    private final EmbyLibrary library;
    private final JSONObject settings;
    private final JSONArray views;

    EmbyEngine(MainActivity activity, Cms.Site site) throws Exception {
        library = EmbyAccounts.open(activity, site.api);
        String key = MediaLibrarySettings.key(site.api);
        android.content.SharedPreferences store =
                activity.getSharedPreferences(MediaLibrarySettings.PREFERENCES, 0);
        settings =
                MediaLibrarySettings.parse(
                        store.getString(MediaLibrarySettings.SETTINGS_PREFIX + key, ""));
        views =
                MediaLibrarySettings.parseViews(
                        store.getString(MediaLibrarySettings.VIEWS_PREFIX + key, ""));
    }

    static void connect(MainActivity a) {
        EmbyAccounts.connect(
                a,
                site ->
                        SourceController.mergeOrSaveConfig(
                                a,
                                Cms.parseConfig(
                                        new JSONObject()
                                                .put("sites", new JSONArray().put(site))
                                                .toString(),
                                        ""),
                                "",
                                true));
    }

    public String home() throws Exception {
        return MediaLibrarySettings.filterHome(library.home(), settings).toString();
    }

    public String homeVideos() throws Exception {
        String parent = MediaLibrarySettings.defaultParent(settings, views);
        return library.list(parent, "", 1).toString();
    }

    public String category(String id, int page) throws Exception {
        String parent = id.isEmpty() ? MediaLibrarySettings.defaultParent(settings, views) : id;
        return library.list(parent, "", page).toString();
    }

    public String search(String term, int page) throws Exception {
        return library.list("", term, page).toString();
    }

    public String detail(String id) throws Exception {
        return library.detail(id).toString();
    }

    public String player(String flag, String id) throws Exception {
        return library.resolve(id, false).toString();
    }

    public boolean requiresExternalRuntime() {
        return false;
    }
}
