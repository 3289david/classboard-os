package kr.classboard.os;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * External video inputs (HDMI, etc.). Boards built on Android's TV input framework expose them as
 * TV inputs; others ship their own "신호 선택" app, which is offered when no TV inputs exist.
 */
final class ExternalInputs {
    private ExternalInputs() {}

    private static final Pattern VENDOR = Pattern.compile("(hdmi|source|signal|inputsource|tvinput|tvplayer|외부 ?입력|입력 ?전환|신호|소스)", Pattern.CASE_INSENSITIVE);

    static JSONObject list(Context c) {
        JSONArray inputs = new JSONArray();
        boolean framework = c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LIVE_TV);
        try {
            TvInputManager tm = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
            if (tm != null) {
                for (TvInputInfo i : tm.getTvInputList()) {
                    if (!i.isPassthroughInput()) continue;
                    String type = typeName(i.getType());
                    CharSequence label = i.loadLabel(c);
                    inputs.put(Util.jo("id", "tv:" + i.getId(), "label", label == null ? type : label.toString(), "type", type,
                            "hdmi", i.getType() == TvInputInfo.TYPE_HDMI));
                }
            }
        } catch (Exception e) {
            L.w("Inputs", "tv inputs", e);
        }
        // vendor apps that switch the panel's source
        JSONArray apps = new JSONArray();
        PackageManager pm = c.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> all = pm.queryIntentActivities(launcher, 0);
        for (ResolveInfo r : all) {
            String pkg = r.activityInfo.packageName;
            String label = String.valueOf(r.loadLabel(pm));
            if (pkg.equals(c.getPackageName())) continue;
            if (VENDOR.matcher(pkg.toLowerCase(Locale.ROOT)).find() || VENDOR.matcher(label).find()) {
                apps.put(Util.jo("id", "pkg:" + pkg, "label", label, "type", "앱"));
            }
        }
        return Util.jo("framework", framework, "inputs", inputs, "apps", apps);
    }

    private static String typeName(int t) {
        switch (t) {
            case TvInputInfo.TYPE_HDMI: return "HDMI";
            case TvInputInfo.TYPE_DISPLAY_PORT: return "DisplayPort";
            case TvInputInfo.TYPE_DVI: return "DVI";
            case TvInputInfo.TYPE_VGA: return "VGA";
            case TvInputInfo.TYPE_COMPONENT: return "컴포넌트";
            case TvInputInfo.TYPE_COMPOSITE: return "컴포지트";
            case TvInputInfo.TYPE_SVIDEO: return "S-Video";
            case TvInputInfo.TYPE_SCART: return "SCART";
            default: return "외부 입력";
        }
    }

    /** Intent that shows the input full screen (the system TV app handles passthrough channels). */
    static Intent intentFor(Context c, String id) {
        if (id == null) return null;
        if (id.startsWith("tv:")) {
            Uri u = TvContract.buildChannelUriForPassthroughInput(id.substring(3));
            return new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        if (id.startsWith("pkg:")) {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(id.substring(4));
            return i == null ? null : i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return null;
    }
}
