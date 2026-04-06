package com.qrscannerpro;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

public class QrScannerPro extends CordovaPlugin {
    private static final int REQUEST_SCAN = 47021;
    static WeakReference<ScannerActivity> activeScannerActivity = new WeakReference<>(null);
    private boolean debugEnabled = false;
    private String debugTag = "QrScannerPro-Android";

    private CallbackContext scanCallbackContext;

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
        if (scanCallbackContext != null) {
            callbackContext.error("A scan session is already active.");
            return true;
        }

        Activity activity = cordova.getActivity();
        Intent intent = new Intent(activity, ScannerActivity.class);
        intent.putExtra(ScannerActivity.EXTRA_OPTIONS_JSON, options.toString());

        scanCallbackContext = callbackContext;
        cordova.setActivityResultCallback(this);
        cordova.startActivityForResult(this, intent, REQUEST_SCAN);
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
        if (scanCallbackContext == null) {
            return;
        }

        if (resultCode == Activity.RESULT_OK && intent != null) {
            debugLog("onActivityResult success");
            String payload = intent.getStringExtra(ScannerActivity.EXTRA_RESULT_JSON);
            if (payload == null) {
                scanCallbackContext.error("Scan finished without payload.");
            } else {
                try {
                    scanCallbackContext.success(new JSONObject(payload));
                } catch (JSONException e) {
                    scanCallbackContext.error("Invalid scan payload.");
                }
            }
        } else if (intent != null && intent.hasExtra(ScannerActivity.EXTRA_ERROR_MESSAGE)) {
            debugLog("onActivityResult error/cancel: " + intent.getStringExtra(ScannerActivity.EXTRA_ERROR_MESSAGE));
            scanCallbackContext.error(intent.getStringExtra(ScannerActivity.EXTRA_ERROR_MESSAGE));
        } else {
            debugLog("onActivityResult cancelled without payload");
            scanCallbackContext.error("Scan cancelled.");
        }
        scanCallbackContext = null;
    }

    @Override
    public void onReset() {
        super.onReset();
        ScannerActivity scannerActivity = activeScannerActivity.get();
        if (scannerActivity != null) {
            scannerActivity.cancelFromPlugin();
        }
    }

    private void debugLog(String message) {
        if (!debugEnabled) {
            return;
        }
        Log.d(debugTag, message);
    }
}
