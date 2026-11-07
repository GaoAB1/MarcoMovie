import com.pilipala.emby.EmbyLibrary;
import com.pilipala.emby.MediaLibrarySettings;
import java.io.IOException;
import org.json.*;

/** Offline checks for the shared media library rules and the Emby views endpoint. */
public final class MediaLibraryChecks {
    public static void main(String[] args) throws Exception {
        serverChecks(args[0]);
        settingsChecks();
        configChecks();
        System.out.println(
                "PASS media library views, address validation, visibility, default library and config merge");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void rejects(String raw) throws Exception {
        try {
            MediaLibrarySettings.normalizeServer(raw);
            throw new AssertionError("accepted server address: " + raw);
        } catch (IOException expected) {
        }
    }

    private static void serverChecks(String address) throws Exception {
        JSONObject session =
                EmbyLibrary.login(address, "fixture", "fixture-password", "fixture-device");
        EmbyLibrary client = new EmbyLibrary(session);
        JSONArray views = client.views();
        check(views.length() == 3, "view count");
        check("电影".equals(views.getJSONObject(0).getString("name")), "view name");
        check("tvshows".equals(views.getJSONObject(1).getString("type")), "view type");
        JSONObject home = client.home();
        check(
                home.getJSONArray("class").getJSONObject(1).getString("type_id").equals("v2"),
                "home category from views");
        check(home.getJSONArray("class").length() == 3, "home category count");
    }

    private static void settingsChecks() throws Exception {
        String normalized = MediaLibrarySettings.normalizeServer(" https://emby.example:8920/ ");
        check(
                "https://emby.example:8920/emby".equals(normalized),
                "server root normalization");
        check(
                "https://emby.example/emby"
                        .equals(MediaLibrarySettings.normalizeServer("https://emby.example/emby/")),
                "existing /emby path");
        rejects("ftp://emby.example");
        rejects("https://emby.example/emby?key=1");
        rejects("https://user@emby.example");
        rejects("https://");

        JSONObject session =
                new JSONObject()
                        .put("base", "https://emby.example:8920/emby")
                        .put("token", "secret-token")
                        .put("user", "u1")
                        .put("device", "fixture-device");
        String text = MediaLibrarySettings.describe(session);
        check("https://emby.example:8920 · u1".equals(text), "session summary");
        check(!text.contains("secret-token") && !text.contains("/emby"), "credential scope");
        check(
                "登录状态已失效，请重新登录".equals(MediaLibrarySettings.describe(null)),
                "missing session summary");
        check("abc-1".equals(MediaLibrarySettings.key("emby://account/abc-1")), "account key");
        try {
            MediaLibrarySettings.key("emby://account/../bad");
            throw new AssertionError("accepted unsafe account key");
        } catch (IOException expected) {
        }

        check(MediaLibrarySettings.parse("{broken").length() == 0, "corrupt settings ignored");
        check(MediaLibrarySettings.parseViews("[broken").length() == 0, "corrupt views ignored");

        JSONArray views =
                new JSONArray()
                        .put(new JSONObject().put("id", "v1").put("name", "电影"))
                        .put(new JSONObject().put("id", "v2").put("name", "剧集"))
                        .put(new JSONObject().put("id", "v3").put("name", "纪录片"));
        JSONObject unset = new JSONObject();
        check(MediaLibrarySettings.visibleIds(unset, views).length() == 3, "all views by default");
        JSONObject subset =
                new JSONObject()
                        .put(
                                "visible",
                                new JSONArray().put("v2").put("missing").put("bad id"));
        check(
                MediaLibrarySettings.visibleIds(subset, views).length() == 1,
                "unknown and invalid ids dropped");
        check(
                MediaLibrarySettings.visibleIds(
                                new JSONObject().put("visible", new JSONArray()), views)
                                .length()
                        == 0,
                "hide every view");

        JSONObject selection = new JSONObject();
        MediaLibrarySettings.setDefault(selection, views, "v1");
        check("v1".equals(MediaLibrarySettings.defaultParent(selection, views)), "default parent");
        MediaLibrarySettings.toggle(selection, views, "v1");
        check(
                MediaLibrarySettings.defaultParent(selection, views).isEmpty(),
                "hidden default cleared");
        MediaLibrarySettings.setDefault(selection, views, "v1");
        check(
                MediaLibrarySettings.defaultParent(selection, views).isEmpty(),
                "hidden default rejected");
        MediaLibrarySettings.toggle(selection, views, "v1");
        MediaLibrarySettings.setDefault(selection, views, "v1");
        check(
                "v1".equals(MediaLibrarySettings.defaultParent(selection, views)),
                "default restored");
        MediaLibrarySettings.setDefault(selection, views, "");
        check(MediaLibrarySettings.defaultParent(selection, views).isEmpty(), "all libraries");

        JSONObject home =
                new JSONObject()
                        .put(
                                "class",
                                new JSONArray()
                                        .put(new JSONObject().put("type_id", "v1"))
                                        .put(new JSONObject().put("type_id", "v2"))
                                        .put(new JSONObject().put("type_id", "v3")));
        check(
                MediaLibrarySettings.filterHome(home, unset).getJSONArray("class").length() == 3,
                "unset selection keeps every category");
        JSONObject filtered =
                MediaLibrarySettings.filterHome(
                        home, new JSONObject().put("visible", new JSONArray().put("v1")));
        check(filtered.getJSONArray("class").length() == 1, "filtered category count");
        check(
                "v1".equals(filtered.getJSONArray("class").getJSONObject(0).getString("type_id")),
                "filtered category identity");
        check(
                MediaLibrarySettings.filterHome(
                                home, new JSONObject().put("visible", new JSONArray()))
                                .getJSONArray("class")
                                .length()
                        == 0,
                "all categories hidden");
    }

    private static void configChecks() throws Exception {
        String config =
                new JSONObject()
                        .put("spider", "keep-me")
                        .put(
                                "sites",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put("key", "cms_1")
                                                        .put("api", "https://cms.example/api.php")))
                        .toString();
        JSONObject site =
                new JSONObject()
                        .put("key", "emby_k1")
                        .put("name", "Emby · me")
                        .put("type", 1)
                        .put("api", "emby://account/k1");
        String merged = MediaLibrarySettings.mergeSite(config, site);
        JSONObject root = new JSONObject(merged);
        check("keep-me".equals(root.getString("spider")), "other config fields kept");
        check(root.getJSONArray("sites").length() == 2, "Emby site added");
        String again = MediaLibrarySettings.mergeSite(merged, site.put("name", "Emby · renamed"));
        check(
                new JSONObject(again).getJSONArray("sites").length() == 2,
                "same account replaces the old site");
        JSONObject after =
                new JSONObject(MediaLibrarySettings.removeSite(again, site.getString("api")));
        check(after.getJSONArray("sites").length() == 1, "Emby site removed");
        check("keep-me".equals(after.getString("spider")), "other fields kept after removal");
        check(
                "cms_1".equals(after.getJSONArray("sites").getJSONObject(0).getString("key")),
                "other source intact");
        check(
                new JSONObject(MediaLibrarySettings.removeSite("", ""))
                        .getJSONArray("sites")
                        .length()
                        == 0,
                "empty config removal");
    }
}
