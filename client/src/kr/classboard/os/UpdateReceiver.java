package kr.classboard.os;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Result of an app update session. The very first self-update asks the person at the board once. */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        CoreService cs = CoreService.get();
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    c.startActivity(confirm);
                } catch (Exception e) {
                    L.w("Update", "confirm", e);
                }
            }
            if (cs != null) cs.updates().setState("설치 확인을 기다리는 중 (화면의 '설치'를 눌러 주세요)");
            return;
        }
        if (status != PackageInstaller.STATUS_SUCCESS && cs != null) {
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            cs.updates().setState("설치 실패: " + (msg == null ? status : msg));
            MainActivity.notifyWeb("update", Util.jo("failed", msg == null ? String.valueOf(status) : msg).toString());
        }
        // on success the app is replaced and restarted (BootReceiver handles MY_PACKAGE_REPLACED)
    }
}
