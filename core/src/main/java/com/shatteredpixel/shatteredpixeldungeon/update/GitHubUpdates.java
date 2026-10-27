package com.shatteredpixel.shatteredpixeldungeon.update;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Net;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.watabou.noosa.Game;
import com.watabou.utils.DeviceCompat;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitHubUpdates extends UpdateService {

	private static final String REPO_OWNER = "NetheritePickaxe";
	private static final String REPO_NAME = "Magic_Ling_Pixel_Dungeon_Online";
	private static final String RELEASES_API = "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/releases";
	private static final String RELEASES_PAGE = "https://github.com/" + REPO_OWNER + "/" + REPO_NAME + "/releases";

	private static final int TIMEOUT = 8000;

	//镜像站：{显示名, URL前缀}。前缀直接拼在 GitHub API 地址前，空前缀即直连。
	//设置项 0 为"自动"，依次尝试直连与全部镜像直到拿到可用数据。
	public static final String[][] MIRRORS = {
			{"直接连接", ""},
			{"gh-proxy.com", "https://gh-proxy.com/"},
			{"ghfast.top", "https://ghfast.top/"},
			{"ghproxy.net", "https://ghproxy.net/"},
			{"hub.gitmirror.com", "https://hub.gitmirror.com/"},
			{"ghproxy.cfd", "https://ghproxy.cfd/"}
	};

	//版本号约定：release 说明里写 internal version number: XXXX，
	//或使用纯数字的 tag（如 2026102100）。两者都取不到时该 release 不参与更新提示。
	private static final Pattern versionCodePattern = Pattern.compile("internal version number: ([0-9]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern tagCodePattern = Pattern.compile("[0-9]{6,}");
	private static final Pattern descPattern = Pattern.compile("(.*?)(\r\n|\n|\r)(\r\n|\n|\r)---", Pattern.DOTALL + Pattern.MULTILINE);

	@Override
	public boolean supportsUpdatePrompts() {
		return true;
	}

	@Override
	public boolean supportsBetaChannel() {
		return true;
	}

	@Override
	public void checkForUpdate(boolean useMetered, boolean includeBetas, UpdateResultCallback callback) {

		if (!useMetered && !Game.platform.connectedToUnmeteredNetwork()) {
			callback.onConnectionFailed();
			return;
		}

		ArrayList<String> urls = new ArrayList<>();
		int mirror = SPDSettings.updateMirror();
		if (mirror > 0 && mirror < MIRRORS.length) {
			urls.add(MIRRORS[mirror][1] + RELEASES_API);
		} else {
			for (String[] m : MIRRORS) urls.add(m[1] + RELEASES_API);
		}

		request(0, urls, includeBetas, callback);
	}

	private static void request(final int index, final ArrayList<String> urls, final boolean includeBetas, final UpdateResultCallback callback) {
		Net.HttpRequest request = new Net.HttpRequest(Net.HttpMethods.GET);
		request.setUrl(urls.get(index));
		request.setHeader("Accept", "application/vnd.github+json");
		request.setHeader("User-Agent", "MLPD-Updater");
		request.setTimeOut(TIMEOUT);

		Gdx.net.sendHttpRequest(request, new Net.HttpResponseListener() {

			private void retry() {
				if (index + 1 < urls.size()) request(index + 1, urls, includeBetas, callback);
				else callback.onConnectionFailed();
			}

			@Override
			public void handleHttpResponse(Net.HttpResponse httpResponse) {
				try {
					AvailableUpdateData update = parse(httpResponse.getResultAsString(), includeBetas);
					if (update != null) callback.onUpdateAvailable(update);
					else callback.onNoUpdateFound();
				} catch (Exception e) {
					retry();
				}
			}

			@Override
			public void failed(Throwable t) {
				retry();
			}

			@Override
			public void cancelled() {
				retry();
			}
		});
	}

	private static AvailableUpdateData parse(String json, boolean includeBetas) throws Exception {
		JsonNode releases = new ObjectMapper().readTree(json);
		if (releases == null || !releases.isArray()) throw new IllegalStateException("not a release list");

		AvailableUpdateData latest = null;
		int latestCode = ShatteredPixelDungeon.versionCode;

		for (JsonNode release : releases) {
			if (release.path("draft").asBoolean(false)) continue;
			if (!includeBetas && release.path("prerelease").asBoolean(false)) continue;

			int code = versionCodeOf(release);
			if (code > latestCode) {
				latestCode = code;
				latest = build(release, code);
			}
		}

		return latest;
	}

	private static int versionCodeOf(JsonNode release) {
		Matcher m = versionCodePattern.matcher(release.path("body").asText(""));
		if (m.find()) return Integer.parseInt(m.group(1));

		Matcher t = tagCodePattern.matcher(release.path("tag_name").asText(""));
		if (t.find()) return Integer.parseInt(t.group());

		return -1;
	}

	private static AvailableUpdateData build(JsonNode release, int code) {
		AvailableUpdateData update = new AvailableUpdateData();
		update.versionCode = code;

		String name = release.path("name").asText("");
		update.versionName = name.isEmpty() ? release.path("tag_name").asText("") : name;

		String body = release.path("body").asText("");
		Matcher m = descPattern.matcher(body);
		update.desc = m.find() ? m.group(1) : body;

		String page = release.path("html_url").asText(RELEASES_PAGE);
		update.URL4 = page;

		String desktop = assetURL(release, ".jar");
		if (desktop == null) desktop = assetURL(release, ".zip");
		update.URL3 = desktop != null ? desktop : page;

		//无 APK 资源时沿用原有约定写 "null"，安卓端据此提示暂无下载
		String android = assetURL(release, ".apk");
		update.URL1 = android != null ? android : "null";

		return update;
	}

	private static String assetURL(JsonNode release, String extension) {
		JsonNode assets = release.path("assets");
		if (!assets.isArray()) return null;
		for (JsonNode asset : assets) {
			String name = asset.path("name").asText("");
			if (name.toLowerCase().endsWith(extension)) {
				String url = asset.path("browser_download_url").asText("");
				if (!url.isEmpty()) return url;
			}
		}
		return null;
	}

	@Override
	public void initializeUpdate(AvailableUpdateData update) {
		Game.platform.openURI(DeviceCompat.isDesktop() ? update.URL3 : update.URL1);
	}

	@Override
	public boolean supportsReviews() {
		return false;
	}

	@Override
	public void initializeReview(ReviewResultCallback callback) {
		callback.onComplete();
	}

	@Override
	public void openReviewURI() {
	}

}
