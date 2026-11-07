package com.pilipala.marcomovie;

import android.content.SharedPreferences;
import com.pilipala.emby.EmbyAccounts;
import com.pilipala.emby.EmbyLibrary;
import com.pilipala.emby.MediaLibrarySettings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.*;

/**
 * Media library settings for user-owned Emby servers. The account reference lives in the config,
 * while credentials, the synced view list and the visible-library selection stay in app-private
 * preferences and are never exported with a config backup.
 */
final class MediaLibraryScreen {
    private final MainActivity a;
    private final SettingsScreen ui;

    MediaLibraryScreen(MainActivity activity) {
        a = activity;
        ui = new SettingsScreen(activity);
    }

    private SharedPreferences store() {
        return a.getSharedPreferences(MediaLibrarySettings.PREFERENCES, 0);
    }

    private JSONObject settings(String api) {
        try {
            String key = MediaLibrarySettings.key(api);
            return MediaLibrarySettings.parse(
                    store().getString(MediaLibrarySettings.SETTINGS_PREFIX + key, ""));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private JSONArray views(String api) {
        try {
            String key = MediaLibrarySettings.key(api);
            return MediaLibrarySettings.parseViews(
                    store().getString(MediaLibrarySettings.VIEWS_PREFIX + key, ""));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private void save(String api, JSONObject selection, JSONArray known) {
        try {
            String key = MediaLibrarySettings.key(api);
            store().edit()
                    .putString(MediaLibrarySettings.SETTINGS_PREFIX + key, selection.toString())
                    .putString(MediaLibrarySettings.VIEWS_PREFIX + key, known.toString())
                    .commit();
            a.closeEngines();
        } catch (Exception e) {
            a.notice("媒体库设置保存失败：" + a.message(e));
        }
    }

    private List<String> accounts() {
        List<String> apis = new ArrayList<>();
        if (a.config != null)
            for (Cms.Site site : a.config.sites)
                if (site.api.startsWith(EmbyLibrary.PREFIX) && !apis.contains(site.api))
                    apis.add(site.api);
        return apis;
    }

    private String name(String api) {
        if (a.config != null)
            for (Cms.Site site : a.config.sites) if (site.api.equals(api)) return site.name;
        return "Emby 服务器";
    }

    private String sessionDetail(String api) {
        try {
            return MediaLibrarySettings.describe(EmbyAccounts.session(a, api));
        } catch (Exception e) {
            return "登录状态不可用";
        }
    }

    void show() {
        ui.page("媒体库", "连接你自己的 Emby 服务器。登录凭据与媒体库设置只保存在本机。");
        List<String> apis = accounts();
        if (apis.isEmpty()) a.paragraph("还没有连接 Emby 服务器。添加后即可在首页浏览服务器里的电影与剧集。");
        for (int i = 0; i < apis.size(); i++) {
            final String target = apis.get(i);
            ui.link(
                    name(target),
                    sessionDetail(target),
                    "media-library:" + i,
                    () -> ui.open(() -> account(target)));
        }
        ui.link("添加 Emby 服务器", "输入服务器地址、用户名和密码", "media-library-add", this::connect);
        a.paragraph("搜索始终覆盖服务器上的全部媒体库；下方设置只影响首页分类与默认浏览范围。");
    }

    private void account(String api) {
        ui.page(name(api), "媒体库设置只对这台设备生效，不影响服务器上的账号。");
        a.paragraph("服务器：" + sessionDetail(api));
        JSONArray known = views(api);
        JSONObject selection = settings(api);
        a.paragraph(known.length() == 0 ? "尚未同步媒体库列表，请先测试连接。" : "已同步 " + known.length() + " 个媒体库。");
        ui.link("测试连接并同步媒体库", "验证登录状态，刷新可选择的媒体库列表", "media-library-sync", () -> sync(api));
        int shown = MediaLibrarySettings.visibleIds(selection, known).length();
        ui.link(
                "首页显示的媒体库",
                known.length() == 0 ? "需先同步媒体库列表" : "已显示 " + shown + " / " + known.length(),
                "media-library-visible",
                () -> chooseVisible(api));
        String parent = MediaLibrarySettings.defaultParent(selection, known);
        ui.link(
                "默认浏览的媒体库",
                parent.isEmpty() ? "全部媒体库" : label(known, parent),
                "media-library-default",
                () -> chooseDefault(api));
        ui.link("重新登录", "凭据过期或需要切换账号时使用", "media-library-relogin", this::connect);
        ui.link("移除此服务器", "同时清除本机登录状态与媒体库设置", "media-library-remove", () -> remove(api));
    }

    private String label(JSONArray known, String id) {
        for (int i = 0; i < known.length(); i++) {
            JSONObject row = known.optJSONObject(i);
            if (row != null && id.equals(row.optString("id"))) return row.optString("name", id);
        }
        return id;
    }

    private void sync(String api) {
        a.toast("正在连接 Emby…");
        a.worker.execute(
                () -> {
                    try {
                        EmbyLibrary library = EmbyAccounts.open(a, api);
                        JSONArray found = library.views();
                        String key = MediaLibrarySettings.key(api);
                        String viewKey = MediaLibrarySettings.VIEWS_PREFIX + key;
                        store().edit().putString(viewKey, found.toString()).commit();
                        a.ui.post(
                                () -> {
                                    a.toast("连接正常 · " + found.length() + " 个媒体库");
                                    a.refresh();
                                });
                    } catch (Exception e) {
                        a.ui.post(() -> a.notice("连接失败：" + a.message(e)));
                    }
                });
    }

    private void chooseVisible(String api) {
        final JSONArray known = views(api);
        if (known.length() == 0) {
            a.notice("请先测试连接并同步媒体库列表。");
            return;
        }
        final JSONObject selection = settings(api);
        String[] labels = new String[known.length()];
        for (int i = 0; i < known.length(); i++) {
            JSONObject row = known.optJSONObject(i);
            String id = row == null ? "" : row.optString("id");
            labels[i] =
                    (MediaLibrarySettings.visible(selection, known, id) ? "✓  " : "○  ")
                            + label(known, id);
        }
        new AppDialog.Builder(a)
                .setTitle("首页显示的媒体库")
                .setItems(
                        labels,
                        (d, index) -> {
                            JSONObject row = known.optJSONObject(index);
                            if (row == null) return;
                            MediaLibrarySettings.toggle(selection, known, row.optString("id"));
                            save(api, selection, known);
                            a.ui.post(() -> chooseVisible(api));
                        })
                .setNegativeButton("完成", (d, i) -> a.refresh())
                .show();
    }

    private void chooseDefault(String api) {
        final JSONArray known = views(api);
        if (known.length() == 0) {
            a.notice("请先测试连接并同步媒体库列表。");
            return;
        }
        final JSONObject selection = settings(api);
        JSONArray shown = MediaLibrarySettings.visibleIds(selection, known);
        String current = MediaLibrarySettings.defaultParent(selection, known);
        String[] labels = new String[shown.length() + 1];
        labels[0] = "全部媒体库";
        int checked = 0;
        for (int i = 0; i < shown.length(); i++) {
            String id = shown.optString(i);
            labels[i + 1] = label(known, id);
            if (id.equals(current)) checked = i + 1;
        }
        new AppDialog.Builder(a)
                .setTitle("默认浏览的媒体库")
                .setSingleChoiceItems(
                        labels,
                        checked,
                        (d, index) -> {
                            String id = index == 0 ? "" : shown.optString(index - 1);
                            MediaLibrarySettings.setDefault(selection, known, id);
                            save(api, selection, known);
                            a.refresh();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void connect() {
        EmbyAccounts.connect(
                a,
                site -> {
                    try {
                        String api = site.getString("api");
                        String base =
                                a.config == null || a.config.raw == null
                                        ? ""
                                        : Cms.withoutComments(a.config.raw);
                        applyConfig(MediaLibrarySettings.mergeSite(base, site));
                        String siteKey = site.getString("key");
                        a.prefs.edit().putString(Prefs.SELECTED_SITE, siteKey).apply();
                        a.ui.post(
                                () -> {
                                    a.toast("已连接 Emby，正在同步媒体库…");
                                    a.refresh();
                                    sync(api);
                                });
                    } catch (Exception e) {
                        throw new IOException("保存 Emby 来源失败：" + a.message(e));
                    }
                });
    }

    private void remove(String api) {
        ui.confirm(
                "移除此 Emby 服务器？",
                () -> {
                    try {
                        if (a.config != null)
                            applyConfig(
                                    MediaLibrarySettings.removeSite(
                                            a.config.raw == null
                                                    ? ""
                                                    : Cms.withoutComments(a.config.raw),
                                            api));
                        EmbyAccounts.forget(a, api);
                        String selected = a.prefs.getString(Prefs.SELECTED_SITE, "");
                        boolean alive = false;
                        if (a.config != null)
                            for (Cms.Site site : a.config.sites)
                                if (site.key.equals(selected)) alive = true;
                        if (!alive) a.prefs.edit().remove(Prefs.SELECTED_SITE).apply();
                        a.toast("已移除 Emby 服务器");
                        a.refresh();
                    } catch (Exception e) {
                        a.notice("移除失败：" + a.message(e));
                    }
                });
    }

    /** Update the in-memory config without jumping back to the home page. */
    private void applyConfig(String raw) throws Exception {
        a.configStore.write(raw);
        Cms.Config config = Cms.parseConfig(raw, a.configOrigin);
        SourcePicker.applyEdits(a, config, a.configOrigin);
        a.config = config;
        a.closeEngines();
    }
}
