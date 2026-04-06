package com.qrscannerpro;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.android.BeepManager;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ScannerActivity extends Activity {
    public static final String EXTRA_OPTIONS_JSON = "qrscannerpro.options";
    public static final String EXTRA_RESULT_JSON = "qrscannerpro.result";
    public static final String EXTRA_ERROR_MESSAGE = "qrscannerpro.error";

    private static final int CAMERA_PERMISSION_REQUEST = 2118;

    private DecoratedBarcodeView barcodeView;
    private ProgressBar loader;
    private OverlayView overlayView;
    private Button flashButton;
    private Button cancelButton;
    private Handler handler;
    private BeepManager beepManager;
    private JSONObject options;
    private boolean torchEnabled = false;
    private boolean resultLocked = false;
    private boolean noDetectionLogged = false;
    private boolean debugEnabled = false;
    private String debugTag = "QrScannerPro-Android";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler = new Handler(Looper.getMainLooper());
        beepManager = new BeepManager(this);

        parseOptions();
        buildUi();
        QrScannerPro.activeScannerActivity = new java.lang.ref.WeakReference<>(this);

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        } else {
            startScanner();
        }
    }

    private void parseOptions() {
        String optionsJson = getIntent().getStringExtra(EXTRA_OPTIONS_JSON);
        try {
            options = optionsJson == null ? new JSONObject() : new JSONObject(optionsJson);
        } catch (JSONException e) {
            options = new JSONObject();
        }
        debugEnabled = options.optBoolean("debug", false);
        debugTag = options.optString("debugTag", "QrScannerPro-Android");
        debugLog("ScannerActivity created with options");
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        barcodeView = new DecoratedBarcodeView(this);
        barcodeView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        barcodeView.getStatusView().setVisibility(View.GONE);
        barcodeView.getViewFinder().setVisibility(View.GONE);
        root.addView(barcodeView);

        overlayView = new OverlayView(this, options);
        root.addView(overlayView);

        loader = new ProgressBar(this);
        FrameLayout.LayoutParams loaderParams = new FrameLayout.LayoutParams(dp(48), dp(48));
        loaderParams.gravity = Gravity.CENTER;
        loader.setLayoutParams(loaderParams);
        loader.setVisibility(View.GONE);
        int loaderColor = parseColor(options.optString("loaderColor", "#00E676"), Color.GREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            loader.getIndeterminateDrawable().setTint(loaderColor);
        }
        root.addView(loader);

        flashButton = createButton(
                options.optString("flashButtonText", "Flash"),
                options.optString("flashButtonIcon", "\u26A1")
        );
        FrameLayout.LayoutParams flashParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        applyBottomButtonLayout(flashParams, true);
        flashButton.setLayoutParams(flashParams);
        flashButton.setOnClickListener(v -> {
            boolean enabled = toggleTorch();
            updateFlashText(enabled);
        });
        flashButton.setVisibility(options.optBoolean("showFlashButton", true) ? View.VISIBLE : View.GONE);
        root.addView(flashButton);

        cancelButton = createButton(
                options.optString("cancelButtonText", "Cancel"),
                options.optString("cancelButtonIcon", "\u2715")
        );
        FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        applyBottomButtonLayout(cancelParams, false);
        cancelButton.setLayoutParams(cancelParams);
        cancelButton.setOnClickListener(v -> cancelScan("Scan cancelled by user."));
        cancelButton.setVisibility(options.optBoolean("showCancelButton", true) ? View.VISIBLE : View.GONE);
        root.addView(cancelButton);

        setContentView(root);
    }

    private Button createButton(String text, String icon) {
        Button button = new Button(this);
        button.setAllCaps(false);
        boolean iconMode = "icon".equalsIgnoreCase(options.optString("buttonMode", "text"));
        int buttonSizeDp = Math.max(36, options.optInt("buttonSize", 52));
        int radiusDp = Math.max(0, options.optInt("buttonCornerRadius", iconMode ? (buttonSizeDp / 2) : 10));
        String label = iconMode ? icon : text;

        button.setText(label);
        button.setTextSize(iconMode ? 22f : 14f);
        if (iconMode) {
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setMinHeight(0);
            button.setMinimumHeight(0);
            button.setWidth(dp(buttonSizeDp));
            button.setHeight(dp(buttonSizeDp));
            button.setPadding(0, 0, 0, 0);
        } else {
            int textWidthDp = Math.max(86, options.optInt("buttonTextWidth", 110));
            button.setMinWidth(dp(textWidthDp));
            button.setPadding(dp(14), dp(8), dp(14), dp(8));
        }
        button.setTextColor(parseColor(options.optString("buttonTextColor", "#FFFFFFFF"), Color.WHITE));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(parseColor(options.optString("buttonBackgroundColor", "#66000000"), Color.argb(102, 0, 0, 0)));
        bg.setCornerRadius(dp(radiusDp));
        button.setBackground(bg);
        return button;
    }

    private void applyBottomButtonLayout(FrameLayout.LayoutParams params, boolean isFlash) {
        int spacing = Math.max(8, options.optInt("buttonSpacing", 16));
        int bottomOffset = Math.max(8, options.optInt("buttonBottomOffset", 46));
        int buttonSizeDp = Math.max(36, options.optInt("buttonSize", 52));
        boolean iconMode = "icon".equalsIgnoreCase(options.optString("buttonMode", "text"));

        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = dp(bottomOffset);
        int textWidthDp = Math.max(86, options.optInt("buttonTextWidth", 110));
        int halfGap = dp((spacing / 2) + (iconMode ? (buttonSizeDp / 2) : (textWidthDp / 2)));
        if (isFlash) {
            params.rightMargin = halfGap;
        } else {
            params.leftMargin = halfGap;
        }
    }

    private void startScanner() {
        debugLog("startScanner");
        Collection<BarcodeFormat> formats = mapFormats(options.optJSONArray("formats"));
        if (!formats.isEmpty()) {
            barcodeView.getBarcodeView().setDecoderFactory(new DefaultDecoderFactory(formats));
        } else {
            List<BarcodeFormat> onlyQr = new ArrayList<>();
            onlyQr.add(BarcodeFormat.QR_CODE);
            onlyQr.add(BarcodeFormat.AZTEC);
            onlyQr.add(BarcodeFormat.DATA_MATRIX);
            barcodeView.getBarcodeView().setDecoderFactory(new DefaultDecoderFactory(onlyQr));
        }

        int zoneWidth = options.optInt("scanZoneWidth", 260);
        int zoneHeight = options.optInt("scanZoneHeight", 260);
        barcodeView.getBarcodeView().setFramingRectSize(new Size(dp(zoneWidth), dp(zoneHeight)));
        barcodeView.resume();
        barcodeView.decodeContinuous(callback);
        debugLog("decodeContinuous started");
        scheduleNoDetectionDiagnostics();
    }

    private void scheduleNoDetectionDiagnostics() {
        noDetectionLogged = false;
        handler.postDelayed(() -> {
            if (!resultLocked && !noDetectionLogged) {
                noDetectionLogged = true;
                debugLog("no QR detected yet");
            }
        }, 3000);
    }

    private final BarcodeCallback callback = new BarcodeCallback() {
        @Override
        public void barcodeResult(BarcodeResult result) {
            if (result == null || result.getText() == null || resultLocked) {
                return;
            }
            debugLog("barcodeResult detected, length=" + result.getText().length());
            resultLocked = true;
            barcodeView.pause();
            if (options.optBoolean("showLoader", true)) {
                loader.setVisibility(View.VISIBLE);
            }

            int delayMs = Math.max(0, options.optInt("loadingDelayMs", 700));
            handler.postDelayed(() -> sendSuccess(result), delayMs);
        }

        @Override
        public void possibleResultPoints(List<ResultPoint> resultPoints) {
            // no-op
        }
    };

    private void sendSuccess(BarcodeResult result) {
        debugLog("sendSuccess");
        if (options.optBoolean("hapticFeedback", true)) {
            beepManager.playBeepSoundAndVibrate();
        }

        JSONObject out = new JSONObject();
        try {
            out.put("text", result.getText());
            out.put("format", result.getBarcodeFormat() == null ? "UNKNOWN" : result.getBarcodeFormat().name());
            out.put("cancelled", false);
            if (options.optBoolean("returnRawBytesBase64", false) && result.getResult() != null && result.getResult().getRawBytes() != null) {
                out.put("rawBytesBase64", Base64.encodeToString(result.getResult().getRawBytes(), Base64.NO_WRAP));
            }
        } catch (JSONException e) {
            finishWithError("Failed to prepare scan result.");
            return;
        }

        Intent data = new Intent();
        data.putExtra(EXTRA_RESULT_JSON, out.toString());
        setResult(Activity.RESULT_OK, data);
        finish();
    }

    public void cancelFromPlugin() {
        runOnUiThread(() -> cancelScan("Scan cancelled."));
    }

    private void cancelScan(String message) {
        debugLog("cancelScan: " + message);
        Intent data = new Intent();
        data.putExtra(EXTRA_ERROR_MESSAGE, message);
        setResult(Activity.RESULT_CANCELED, data);
        finish();
    }

    private void finishWithError(String message) {
        Intent data = new Intent();
        data.putExtra(EXTRA_ERROR_MESSAGE, message);
        setResult(Activity.RESULT_CANCELED, data);
        finish();
    }

    public boolean toggleTorch() {
        debugLog("toggleTorch current=" + torchEnabled);
        if (!isFlashAvailable()) {
            debugLog("toggleTorch ignored: flash not available");
            torchEnabled = false;
            return false;
        }
        setTorch(!torchEnabled);
        debugLog("toggleTorch result=" + torchEnabled);
        return torchEnabled;
    }

    public void setTorch(boolean enabled) {
        if (!isFlashAvailable()) {
            torchEnabled = false;
            updateFlashText(false);
            debugLog("setTorch ignored: flash not available");
            return;
        }
        try {
            barcodeView.setTorch(enabled);
            torchEnabled = enabled;
            debugLog("setTorch=" + enabled);
        } catch (RuntimeException e) {
            torchEnabled = false;
            debugLog("setTorch failed: " + e.getMessage());
        }
        updateFlashText(torchEnabled);
    }

    public boolean isTorchEnabled() {
        return torchEnabled;
    }

    public boolean isFlashAvailable() {
        return getPackageManager().hasSystemFeature("android.hardware.camera.flash");
    }

    private void updateFlashText(boolean enabled) {
        String base = options.optString("flashButtonText", "Flash");
        boolean iconMode = "icon".equalsIgnoreCase(options.optString("buttonMode", "text"));
        if (iconMode) {
            flashButton.setText(enabled ? "\uD83D\uDCA1" : options.optString("flashButtonIcon", "\u26A1"));
        } else {
            flashButton.setText(enabled ? base + " ON" : base);
        }
    }

    private Collection<BarcodeFormat> mapFormats(JSONArray arr) {
        List<BarcodeFormat> formats = new ArrayList<>();
        if (arr == null) {
            return formats;
        }
        for (int i = 0; i < arr.length(); i++) {
            String name = arr.optString(i, "").trim().toUpperCase();
            try {
                if (!name.isEmpty()) {
                    formats.add(BarcodeFormat.valueOf(name));
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return formats;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
    }

    private int parseColor(String color, int fallback) {
        try {
            return Color.parseColor(color);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (barcodeView != null && !resultLocked) {
            barcodeView.resume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (barcodeView != null) {
            barcodeView.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        debugLog("onDestroy");
        handler.removeCallbacksAndMessages(null);
        QrScannerPro.activeScannerActivity = new java.lang.ref.WeakReference<>(null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            debugLog("camera permission granted");
            startScanner();
        } else {
            debugLog("camera permission denied");
            finishWithError("Camera permission denied.");
        }
    }

    private void debugLog(String message) {
        if (!debugEnabled) {
            return;
        }
        Log.d(debugTag, message);
    }

    private static class OverlayView extends View {
        private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scanLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final JSONObject options;
        private Rect scanRect;
        private final Path overlayPath = new Path();
        private final RectF scanRectF = new RectF();
        private float lineProgress = 0f;
        private ValueAnimator animator;

        OverlayView(Activity context, JSONObject options) {
            super(context);
            this.options = options;
            setWillNotDraw(false);
            framePaint.setStyle(Paint.Style.STROKE);
            scanLinePaint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            int zoneW = dpLocal(options.optInt("scanZoneWidth", 260));
            int zoneH = dpLocal(options.optInt("scanZoneHeight", 260));
            int offsetY = dpLocal(options.optInt("scanZoneOffsetY", 0));
            int left = (w - zoneW) / 2;
            int top = (h - zoneH) / 2 + offsetY;
            scanRect = new Rect(left, top, left + zoneW, top + zoneH);
            startAnimation();
        }

        private void startAnimation() {
            if (!options.optBoolean("animateScanLine", true)) {
                return;
            }
            if (animator != null) {
                animator.cancel();
            }
            int duration = Math.max(600, options.optInt("scanLineDurationMs", 1800));
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(duration);
            animator.setInterpolator(new LinearInterpolator());
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.REVERSE);
            animator.addUpdateListener(animation -> {
                lineProgress = (float) animation.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (scanRect == null) {
                return;
            }
            int overlayColor = parseColorLocal(options.optString("overlayColor", "#88000000"), Color.argb(136, 0, 0, 0));
            int frameColor = parseColorLocal(options.optString("frameColor", "#00E676"), Color.GREEN);
            int lineColor = parseColorLocal(options.optString("scanLineColor", "#00E676"), Color.GREEN);
            int frameThickness = dpLocal(Math.max(1, options.optInt("frameThickness", 3)));
            int lineThickness = dpLocal(Math.max(1, options.optInt("scanLineThickness", 3)));
            float radius = dpLocal(Math.max(0, options.optInt("frameCornerRadius", 12)));

            if (options.optBoolean("darkenOutside", true)) {
                overlayPaint.setColor(overlayColor);
                overlayPath.reset();
                overlayPath.setFillType(Path.FillType.EVEN_ODD);
                overlayPath.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
                scanRectF.set(scanRect.left, scanRect.top, scanRect.right, scanRect.bottom);
                overlayPath.addRoundRect(scanRectF, radius, radius, Path.Direction.CW);
                canvas.drawPath(overlayPath, overlayPaint);
            }

            framePaint.setColor(frameColor);
            framePaint.setStrokeWidth(frameThickness);
            canvas.drawRoundRect(
                    scanRect.left,
                    scanRect.top,
                    scanRect.right,
                    scanRect.bottom,
                    radius,
                    radius,
                    framePaint
            );

            if (options.optBoolean("animateScanLine", true)) {
                scanLinePaint.setColor(lineColor);
                int y = (int) (scanRect.top + (scanRect.height() * lineProgress));
                canvas.drawRect(scanRect.left + frameThickness, y, scanRect.right - frameThickness, y + lineThickness, scanLinePaint);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (animator != null) {
                animator.cancel();
            }
        }

        private int dpLocal(int value) {
            return (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    value,
                    getResources().getDisplayMetrics()
            );
        }

        private int parseColorLocal(String color, int fallback) {
            try {
                return Color.parseColor(color);
            } catch (Exception ignored) {
                return fallback;
            }
        }
    }
}
