package kr.classboard.os;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/** Invisible activity that asks the system for screen-capture consent, then hands off to CaptureService. */
public class CaptureActivity extends Activity {
    private static final int REQ = 1;
    private boolean record;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        record = getIntent().getBooleanExtra("record", false);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ && res == RESULT_OK && data != null) {
            Intent i = new Intent(this, CaptureService.class)
                    .setAction(record ? CaptureService.ACTION_RECORD : CaptureService.ACTION_SHOT)
                    .putExtra("code", res)
                    .putExtra("data", data);
            startForegroundService(i);
        } else {
            MainActivity.notifyWeb("capture", Util.jo("ok", false, "error", "화면 캡처 권한이 거부되었습니다").toString());
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
