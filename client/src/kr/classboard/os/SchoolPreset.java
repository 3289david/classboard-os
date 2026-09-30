package kr.classboard.os;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Built-in school: 중동중학교 (서울특별시교육청). Applied when the hub starts without a school,
 * so a new board works without searching. Values come from NEIS, Comcigan, the school homepage
 * (joongdong.sen.ms.kr, 일과표 page) and OpenStreetMap; admins can still change them in 설정.
 */
public final class SchoolPreset {
    private SchoolPreset() {}

    public static JSONObject config() {
        JSONObject school = Util.jo(
                "atpt", "B10", "atptName", "서울특별시교육청", "code", "7091455", "name", "중동중학교", "kind", "중학교",
                "address", "서울특별시 강남구 일원로8길 37", "tel", "070-7092-9300", "homepage", "https://joongdong.sen.ms.kr/",
                "region", "서울특별시");
        JSONObject comci = Util.jo("region", "서울", "name", "중동중학교", "code", 38243);
        // 학교 홈페이지 일과표: 1교시 09:00 ... 7교시 15:20~16:05, 45분 수업
        String[][] bell = {{"09:00", "09:45"}, {"09:55", "10:40"}, {"10:50", "11:35"}, {"11:45", "12:30"},
                {"13:30", "14:15"}, {"14:25", "15:10"}, {"15:20", "16:05"}};
        JSONArray b = new JSONArray();
        for (String[] x : bell) b.put(Util.jo("start", x[0], "end", x[1]));
        JSONArray dayEvents = new JSONArray()
                .put(Util.jo("name", "주번 활동 · 학급 조회", "start", "08:40", "end", "08:50"))
                .put(Util.jo("name", "점심시간", "start", "12:30", "end", "13:30"));
        JSONArray boards = new JSONArray()
                .put(Util.jo("menuId", "21440", "name", "가정통신문"))
                .put(Util.jo("menuId", "193205", "name", "가정통신문(교육청)"))
                .put(Util.jo("menuId", "21439", "name", "공지사항"))
                .put(Util.jo("menuId", "144205", "name", "영양 소식"));
        return Util.jo("school", school, "comci", comci, "periodMinutes", 45, "bell", b, "dayEvents", dayEvents,
                "lat", 37.4887747, "lon", 127.07821, "locationName", "중동중학교", "homepageBoards", boards,
                "contacts", new JSONArray().put(Util.jo("name", "대표전화", "phone", "070-7092-9300", "dept", "중동중학교"))
                        .put(Util.jo("name", "팩스", "phone", "02-445-9882", "dept", "중동중학교")));
    }

    /** NEIS key: the admin's key from 설정 if set, otherwise the one built into the APK. */
    public static String neisKey(JSONObject config) {
        String k = config == null ? "" : config.optString("neisKey", "");
        return k.isEmpty() ? Secrets.NEIS_KEY : k;
    }
}
