package com.tools.soundsetter;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Optional elevated (ADB) access through Shizuku / Sui.
 *
 * When an OEM skin (MIUI/HyperOS and others) silently refuses the normal
 * WRITE_SETTINGS write, shell uid 2000 can still write the settings rows
 * directly with: settings put system ringtone &lt;content-uri&gt;
 *
 * Everything here degrades gracefully: if Shizuku is not installed, not
 * running or not authorized, every method simply reports "unavailable" and the
 * app continues with the normal path. No other app is required for normal use.
 */
public final class ShizukuBridge {

    private static final String TAG = "SoundSetter";
    public static final String MANAGER_PACKAGE = "moe.shizuku.manager";
    public static final int REQ_PERMISSION = 0x5A17;

    private ShizukuBridge() {}

    public static boolean isManagerInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(MANAGER_PACKAGE, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isBinderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isPreV11() {
        try {
            return Shizuku.isPreV11();
        } catch (Throwable t) {
            return true;
        }
    }

    /** True when commands can actually be executed as shell (uid 2000). */
    public static boolean isAuthorized() {
        try {
            return isBinderAlive() && !isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestPermission() {
        try {
            Shizuku.requestPermission(REQ_PERMISSION);
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku.requestPermission failed", t);
        }
    }

    public static void openManager(Context ctx) {
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(MANAGER_PACKAGE);
            if (i != null) {
                ctx.startActivity(i);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://shizuku.rikka.app/download/")));
        } catch (Throwable ignored) {
        }
    }

    /** Result of one shell command run through Shizuku. */
    public static final class ExecResult {
        public final int code;
        public final String out;
        public final String err;

        ExecResult(int code, String out, String err) {
            this.code = code;
            this.out = out == null ? "" : out.trim();
            this.err = err == null ? "" : err.trim();
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("exit=").append(code);
            if (out.length() > 0) sb.append(" out=").append(out);
            if (err.length() > 0) sb.append(" err=").append(err);
            return sb.toString();
        }
    }

    /**
     * Runs a command via the Shizuku service (IShizukuService#newProcess). Runs as
     * shell uid 2000 when Shizuku is adb-backed, or as root when rooted - both
     * identities may write the system sound settings rows that normal apps cannot.
     */
    public static ExecResult exec(String[] cmd) throws Exception {
        IBinder binder = Shizuku.getBinder();
        if (binder == null || !binder.pingBinder()) {
            throw new IllegalStateException("Shizuku service binder is not available");
        }
        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        IRemoteProcess proc = service.newProcess(cmd, null, null);
        if (proc == null) {
            throw new IllegalStateException("Shizuku could not start the requested process");
        }
        ParcelFileDescriptor outPfd = null;
        ParcelFileDescriptor errPfd = null;
        try {
            outPfd = proc.getInputStream();
            String out = readFully(outPfd);
            outPfd = null;
            errPfd = proc.getErrorStream();
            String err = readFully(errPfd);
            errPfd = null;
            int code = proc.waitFor();
            return new ExecResult(code, out, err);
        } finally {
            try {
                proc.destroy();
            } catch (Throwable ignored) {
            }
            closeQuietly(outPfd);
            closeQuietly(errPfd);
        }
    }

    private static String readFully(ParcelFileDescriptor pfd) throws IOException {
        if (pfd == null) return "";
        StringBuilder sb = new StringBuilder();
        FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, "UTF-8"));
                if (sb.length() > 64 * 1024) break;
            }
        } finally {
            try {
                fis.close();
            } catch (IOException ignored) {
            }
        }
        return sb.toString();
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd == null) return;
        try {
            pfd.close();
        } catch (Throwable ignored) {
        }
    }
}
