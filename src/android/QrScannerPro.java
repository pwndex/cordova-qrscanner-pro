package com.qrscannerpro;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

public class QrScannerPro extends CordovaPlugin {
    private static final int REQUEST_SCAN = 47021;
    /** Short pause so the previous ScannerActivity can release the camera before the next open (avoids rapid open/close on repeat scans). */
    private static final int SCAN_COOLDOWN_MS = 120;
    static WeakReference<ScannerActivity> activeScannerActivity = new WeakReference<>(null);
    private boolean debugEnabled = false;
    private String debugTag = "QrScannerPro-Android";

    private CallbackContext scanCallbackContext;
    private final Object scanLock = new Object();
    private long lastScanSessionEndedAtMs;
    private long nextScanSessionToken;
    private long pendingScanSessionTokenForResult;

    @Override
    protected void pluginInitialize() {
        super.pluginInitialize();
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        switch (action) {
            case "scan":
                JSONObject options = args.length() > 0 ? args.optJSONObject(0) : new JSONObject();
                return startScan(options == null ? new JSONObject() : options, callbackContext);
            case "cancel":
                return cancelScan(callbackContext);
            case "toggleFlash":
                return toggleFlash(callbackContext);
            case "setFlash":
                return setFlash(args.optBoolean(0, false), callbackContext);
            case "isFlashAvailable":
                return isFlashAvailable(callbackContext);
            default:
                return false;
        }
    }

    private boolean startScan(JSONObject options, CallbackContext callbackContext) {
        debugEnabled = options.optBoolean("debug", false);
        debugTag = options.optString("debugTag", "QrScannerPro-Android");
        debugLog("scan called");
        final long sessionToken = ++nextScanSessionToken;
        final CallbackContext pendingCb = callbackContext;

        synchronized (scanLock) {
            if (scanCallbackContext != null) {
                callbackContext.error("A scan session is already active.");
                return true;
            }

            Activity activity = cordova.getActivity();
            Intent intent = new Intent(activity, ScannerActivity.class);
            intent.putExtra(ScannerActivity.EXTRA_OPTIONS_JSON, options.toString());
            intent.putExtra(ScannerActivity.EXTRA_SCAN_SESSION_TOKEN, sessionToken);

            scanCallbackContext = callbackContext;
            cordova.setActivityResultCallback(this);
            // Bind expected result token immediately to avoid stale onActivityResult
            // hijacking a newly scheduled scan during the launch cooldown window.
            pendingScanSessionTokenForResult = sessionToken;

            long delayMs = Math.max(0, SCAN_COOLDOWN_MS - (System.currentTimeMillis() - lastScanSessionEndedAtMs));
            if (delayMs > 0) {
                debugLog("delaying startActivityForResult by " + delayMs + " ms (camera cooldown)");
            }

            Handler main = new Handler(Looper.getMainLooper());
            main.postDelayed(() -> {
                synchronized (scanLock) {
                    if (scanCallbackContext != pendingCb) {
                        debugLog("startScan runnable skipped (session replaced or cleared)");
                        return;
                    }
                    cordova.startActivityForResult(QrScannerPro.this, intent, REQUEST_SCAN);
                }
            }, delayMs);
        }
        return true;
    }

    private boolean cancelScan(CallbackContext callbackContext) {
        debugLog("cancel action called from JS");
        ScannerActivity scannerActivity = activeScannerActivity.get();
        if (scannerActivity != null) {
            scannerActivity.cancelFromPlugin();
            callbackContext.success();
            return true;
        }
        callbackContext.success();
        return true;
    }

    private boolean toggleFlash(CallbackContext callbackContext) {
        debugLog("toggleFlash action called from JS");
        ScannerActivity scannerActivity = activeScannerActivity.get();
        if (scannerActivity == null) {
            callbackContext.error("No active scan session.");
            return true;
        }
        if (!scannerActivity.isFlashAvailable()) {
            callbackContext.error("Flash is not available on this device.");
            return true;
        }
        boolean enabled = scannerActivity.toggleTorch();
        JSONObject out = new JSONObject();
        try {
            out.put("enabled", enabled);
            callbackContext.success(out);
        } catch (JSONException e) {
            callbackContext.error(e.getMessage());
        }
        return true;
    }

    private boolean setFlash(boolean enabled, CallbackContext callbackContext) {
        debugLog("setFlash action called from JS");
        ScannerActivity scannerActivity = activeScannerActivity.get();
        if (scannerActivity == null) {
            callbackContext.error("No active scan session.");
            return true;
        }
        if (!scannerActivity.isFlashAvailable()) {
            callbackContext.error("Flash is not available on this device.");
            return true;
        }
        scannerActivity.setTorch(enabled);
        JSONObject out = new JSONObject();
        try {
            out.put("enabled", scannerActivity.isTorchEnabled());
            callbackContext.success(out);
        } catch (JSONException e) {
            callbackContext.error(e.getMessage());
        }
        return true;
    }

    private boolean isFlashAvailable(CallbackContext callbackContext) {
        ScannerActivity scannerActivity = activeScannerActivity.get();
        boolean hasFlash = scannerActivity != null
                ? scannerActivity.isFlashAvailable()
                : cordova.getActivity().getPackageManager().hasSystemFeature("android.hardware.camera.flash");
        JSONObject out = new JSONObject();
        try {
            out.put("available", hasFlash);
            callbackContext.success(out);
        } catch (JSONException e) {
            callbackContext.error(e.getMessage());
        }
        return true;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode != REQUEST_SCAN) {
            return;
        }
        final CallbackContext cb;
        synchronized (scanLock) {
            if (scanCallbackContext == null) {
                debugLog("onActivityResult ignored (no pending callback — duplicate delivery?)");
                return;
            }
            cb = scanCallbackContext;
            long got = intent != null ? intent.getLongExtra(ScannerActivity.EXTRA_SCAN_SESSION_TOKEN, -1L) : -1L;
            if (pendingScanSessionTokenForResult > 0 && got != pendingScanSessionTokenForResult) {
                debugLog("onActivityResult ignored (session token mismatch, got=" + got + " pending=" + pendingScanSessionTokenForResult + ")");
                return;
            }
            scanCallbackContext = null;
            pendingScanSessionTokenForResult = 0;
            lastScanSessionEndedAtMs = System.currentTimeMillis();
        }

        if (resultCode == Activity.RESULT_OK && intent != null) {
            debugLog("onActivityResult success");
            String payload = intent.getStringExtra(ScannerActivity.EXTRA_RESULT_JSON);
            if (payload == null) {
                cb.error("Scan finished without payload.");
            } else {
                try {
                    cb.success(new JSONObject(payload));
                } catch (JSONException e) {
                    cb.error("Invalid scan payload.");
                }
            }
        } else if (intent != null && intent.hasExtra(ScannerActivity.EXTRA_ERROR_MESSAGE)) {
            debugLog("onActivityResult error/cancel: " + intent.getStringExtra(ScannerActivity.EXTRA_ERROR_MESSAGE));
            cb.error(intent.getStringExtra(ScannerActivity.EXTRA_ERROR_MESSAGE));
        } else {
            debugLog("onActivityResult cancelled without payload");
            cb.error("Scan cancelled.");
        }
    }

    @Override
    public void onReset() {
        super.onReset();
        CallbackContext cbToCancel = null;
        ScannerActivity scannerActivity;
        synchronized (scanLock) {
            if (scanCallbackContext == null) {
                return;
            }
            scannerActivity = activeScannerActivity.get();
            if (scannerActivity == null) {
                cbToCancel = scanCallbackContext;
                scanCallbackContext = null;
                pendingScanSessionTokenForResult = 0;
            }
        }
        if (scannerActivity != null) {
            scannerActivity.cancelFromPlugin();
            return;
        }
        if (cbToCancel != null) {
            cbToCancel.error("Scan cancelled.");
        }
    }

    @Override
    public void onRestoreStateForActivityResult(android.os.Bundle state, CallbackContext callbackContext) {
        synchronized (scanLock) {
            scanCallbackContext = callbackContext;
            // Token is unknown after process recreation; accept next result for REQUEST_SCAN.
            pendingScanSessionTokenForResult = 0;
        }
        // Keep callback alive until native activity returns a result.
        PluginResult pending = new PluginResult(PluginResult.Status.NO_RESULT);
        pending.setKeepCallback(true);
        callbackContext.sendPluginResult(pending);
        debugLog("onRestoreStateForActivityResult: callback restored");
    }

    private void debugLog(String message) {
        if (!debugEnabled) {
            return;
        }
        Log.d(debugTag, message);
    }
}
