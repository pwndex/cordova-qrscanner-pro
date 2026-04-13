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
import android.graphics.Typeface;
import android.view.WindowInsets;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.webkit.WebSettings;
import android.webkit.WebView;

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
import java.nio.charset.StandardCharsets;

public class ScannerActivity extends Activity {
    public static final String EXTRA_OPTIONS_JSON = "qrscannerpro.options";
    public static final String EXTRA_RESULT_JSON = "qrscannerpro.result";
    public static final String EXTRA_ERROR_MESSAGE = "qrscannerpro.error";
    /** Echoed on every result so Cordova can ignore stale/duplicate onActivityResult deliveries. */
    public static final String EXTRA_SCAN_SESSION_TOKEN = "qrscannerpro.sessionToken";

    private static final int CAMERA_PERMISSION_REQUEST = 2118;

    private DecoratedBarcodeView barcodeView;
    private ProgressBar loader;
    private OverlayView overlayView;
    private TextView headerTextView;
    private FrameLayout flashButtonContainer;
    private FrameLayout cancelButtonContainer;
    private Button flashButton;
    private Button cancelButton;
    private WebView flashButtonSvgView;
    private WebView cancelButtonSvgView;
    private Handler handler;
    private BeepManager beepManager;
    private JSONObject options;
    private boolean torchEnabled = false;
    private boolean resultLocked = false;
    private boolean noDetectionLogged = false;
    private boolean debugEnabled = false;
    private String debugTag = "QrScannerPro-Android";
    private long sessionToken;
    private int appliedSafeTopPx = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler = new Handler(Looper.getMainLooper());
        beepManager = new BeepManager(this);

        parseOptions();
        sessionToken = getIntent().getLongExtra(EXTRA_SCAN_SESSION_TOKEN, 0L);
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

        headerTextView = buildHeaderView();
        if (headerTextView != null) {
            root.addView(headerTextView);
        }

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

        flashButtonContainer = new FrameLayout(this);
        flashButton = createButton(true);
        flashButtonContainer.addView(flashButton);
        FrameLayout.LayoutParams flashParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        applyBottomButtonLayout(flashParams, true);
        flashButtonContainer.setLayoutParams(flashParams);
        flashButton.setOnClickListener(v -> {
            boolean isActive = toggleTorch();
            setFlashVisualState(isActive);
        });
        flashButtonContainer.setVisibility(options.optBoolean("showFlashButton", true) ? View.VISIBLE : View.GONE);
        root.addView(flashButtonContainer);

        cancelButtonContainer = new FrameLayout(this);
        cancelButton = createButton(false);
        cancelButtonContainer.addView(cancelButton);
        FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        applyBottomButtonLayout(cancelParams, false);
        cancelButtonContainer.setLayoutParams(cancelParams);
        cancelButton.setOnClickListener(v -> cancelScan("Scan cancelled by user."));
        cancelButtonContainer.setVisibility(options.optBoolean("showCancelButton", true) ? View.VISIBLE : View.GONE);
        root.addView(cancelButtonContainer);

        setContentView(root);
        applyTopSafeAreaInsets();
        attachSvgIconIfNeeded(true);
        attachSvgIconIfNeeded(false);
        refreshFlashButtonAppearance();
        refreshCancelButtonAppearance();
    }

    private TextView buildHeaderView() {
        String title = options.optString("headerText", "").trim();
        if (title.isEmpty()) {
            return null;
        }
        TextView header = new TextView(this);
        header.setText(title);
        header.setGravity(Gravity.CENTER);
        header.setTextColor(parseColor(options.optString("headerTextColor", "#FFFFFFFF"), Color.WHITE));
        int headerHeight = Math.max(32, options.optInt("headerHeight", 56));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(headerHeight)
        );
        params.gravity = Gravity.TOP;
        header.setLayoutParams(params);
        header.setBackgroundColor(parseColor(options.optString("headerBackgroundColor", "#00000000"), Color.TRANSPARENT));
        int headerPadding = Math.max(0, options.optInt("headerPadding", 12));
        header.setPadding(dp(headerPadding), dp(headerPadding), dp(headerPadding), dp(headerPadding));
        float headerTextSize = Math.max(12f, (float) options.optDouble("headerFontSize", 18));
        header.setTextSize(headerTextSize);
        Typeface satoshi = Typeface.create("Satoshi", Typeface.NORMAL);
        if (satoshi != null) {
            header.setTypeface(satoshi);
        }
        return header;
    }

    private Button createButton(boolean isFlashButton) {
        Button button = new Button(this);
        button.setAllCaps(false);
        boolean iconMode = isIconMode();
        int buttonSizeDp = Math.max(36, options.optInt("buttonSize", 52));
        int buttonHeightDp = getButtonHeightDp();
        int buttonWidthDp = getButtonWidthDp();
        int radiusDp = Math.max(0, options.optInt("buttonCornerRadius", iconMode ? (buttonHeightDp / 2) : 10));
        String label = getButtonLabel(isFlashButton);
        float contentSizeSp = getButtonContentSizeSp();

        button.setText(label);
        button.setTextSize(contentSizeSp);
        if (iconMode) {
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setMinHeight(0);
            button.setMinimumHeight(0);
            button.setWidth(dp(buttonWidthDp));
            button.setHeight(dp(buttonHeightDp));
            button.setPadding(0, 0, 0, 0);
        } else {
            button.setMinWidth(dp(buttonWidthDp));
            button.setMinimumWidth(dp(buttonWidthDp));
            button.setMinHeight(dp(buttonHeightDp));
            button.setMinimumHeight(dp(buttonHeightDp));
            button.setPadding(dp(14), dp(8), dp(14), dp(8));
        }
        FrameLayout.LayoutParams inner = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        button.setLayoutParams(inner);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(getButtonBackgroundColor(isFlashButton, false));
        bg.setCornerRadius(dp(radiusDp));
        button.setBackground(bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setBackgroundTintList(null);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                button.setForeground(null);
            }
        }
        button.setTextColor(getButtonTextColor(isFlashButton, false));
        return button;
    }

    private void applyBottomButtonLayout(FrameLayout.LayoutParams params, boolean isFlash) {
        int spacing = Math.max(8, options.optInt("buttonSpacing", 16));
        int bottomOffset = Math.max(8, options.optInt("buttonBottomOffset", 46));
        int buttonWidthDp = getButtonWidthDp();
        int buttonHeightDp = getButtonHeightDp();

        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.width = dp(buttonWidthDp);
        params.height = dp(buttonHeightDp);
        params.bottomMargin = dp(bottomOffset) + getSafeAreaBottomPx();
        int halfGapPx = dp((spacing / 2) + (buttonWidthDp / 2));
        if (isFlash) {
            params.leftMargin = 0;
            params.rightMargin = 0;
            flashButtonContainer.setTranslationX(-halfGapPx);
        } else {
            params.leftMargin = 0;
            params.rightMargin = 0;
            cancelButtonContainer.setTranslationX(halfGapPx);
        }
    }

    private int getButtonHeightDp() {
        return Math.max(32, options.optInt("buttonHeight", options.optInt("buttonSize", 52)));
    }

    private int getButtonWidthDp() {
        if (isIconMode()) {
            return Math.max(32, options.optInt("buttonSize", 52));
        }
        return Math.max(64, options.optInt("buttonWidth", 110));
    }

    private float getButtonContentSizeSp() {
        float fallback = isIconMode() ? 22f : 15f;
        return Math.max(10f, (float) options.optDouble("buttonContentSize", fallback));
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
        data.putExtra(EXTRA_SCAN_SESSION_TOKEN, sessionToken);
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
        data.putExtra(EXTRA_SCAN_SESSION_TOKEN, sessionToken);
        data.putExtra(EXTRA_ERROR_MESSAGE, message);
        setResult(Activity.RESULT_CANCELED, data);
        finish();
    }

    private void finishWithError(String message) {
        Intent data = new Intent();
        data.putExtra(EXTRA_SCAN_SESSION_TOKEN, sessionToken);
        data.putExtra(EXTRA_ERROR_MESSAGE, message);
        setResult(Activity.RESULT_CANCELED, data);
        finish();
    }

    public boolean toggleTorch() {
        debugLog("toggleTorch current=" + torchEnabled);
        if (!isFlashAvailable()) {
            debugLog("toggleTorch ignored: flash not available");
            torchEnabled = false;
            setFlashVisualState(false);
            return false;
        }
        setTorch(!torchEnabled);
        debugLog("toggleTorch result=" + torchEnabled);
        return torchEnabled;
    }

    public void setTorch(boolean enabled) {
        boolean previous = torchEnabled;
        if (!isFlashAvailable()) {
            torchEnabled = false;
            setFlashVisualState(false);
            debugLog("setTorch ignored: flash not available");
            return;
        }
        try {
            barcodeView.setTorch(enabled);
            torchEnabled = enabled;
            debugLog("setTorch=" + enabled);
        } catch (RuntimeException e) {
            torchEnabled = previous;
            debugLog("setTorch failed: " + e.getMessage());
        }
        setFlashVisualState(torchEnabled);
    }

    public boolean isTorchEnabled() {
        return torchEnabled;
    }

    public boolean isFlashAvailable() {
        return getPackageManager().hasSystemFeature("android.hardware.camera.flash");
    }

    private boolean isIconMode() {
        return "icon".equalsIgnoreCase(options.optString("buttonMode", "text"));
    }

    private String getButtonLabel(boolean isFlashButton) {
        if (!isIconMode()) {
            if (isFlashButton) {
                String base = options.optString("flashButtonText", "Flash");
                return torchEnabled ? base + " ON" : base;
            }
            return options.optString("cancelButtonText", "Cancel");
        }
        return isFlashButton
                ? options.optString("flashButtonIcon", "\u26A1")
                : options.optString("cancelButtonIcon", "\u2715");
    }

    private int getButtonTextColor(boolean isFlashButton, boolean active) {
        String globalColor = options.optString("buttonTextColor", "#FFFFFFFF");
        String buttonColor;
        if (isFlashButton) {
            buttonColor = options.optString(active ? "flashButtonActiveTextColor" : "flashButtonTextColor", "");
        } else {
            buttonColor = options.optString("cancelButtonTextColor", "");
        }
        return parseColor(buttonColor.isEmpty() ? globalColor : buttonColor, Color.WHITE);
    }

    private int getButtonBackgroundColor(boolean isFlashButton, boolean active) {
        String globalColor = options.optString("buttonBackgroundColor", "#66000000");
        String buttonColor;
        if (isFlashButton) {
            buttonColor = options.optString(active ? "flashButtonActiveBackgroundColor" : "flashButtonBackgroundColor", "");
        } else {
            buttonColor = options.optString("cancelButtonBackgroundColor", "");
        }
        return parseColor(buttonColor.isEmpty() ? globalColor : buttonColor, Color.argb(102, 0, 0, 0));
    }

    private void styleButtonForState(Button button, boolean isFlashButton, boolean active) {
        button.setTextColor(getButtonTextColor(isFlashButton, active));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        int buttonSizeDp = Math.max(36, options.optInt("buttonSize", 52));
        int radiusDp = Math.max(0, options.optInt("buttonCornerRadius", isIconMode() ? (buttonSizeDp / 2) : 10));
        bg.setCornerRadius(dp(radiusDp));
        bg.setColor(getButtonBackgroundColor(isFlashButton, active));
        button.setBackground(bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setBackgroundTintList(null);
        }
    }

    private String getButtonIconSource(boolean isFlashButton) {
        return options.optString(isFlashButton ? "flashButtonIcon" : "cancelButtonIcon",
                isFlashButton ? "\u26A1" : "\u2715").trim();
    }

    private boolean isSvgValue(String iconValue) {
        String value = iconValue == null ? "" : iconValue.trim().toLowerCase();
        return value.startsWith("<svg") || value.startsWith("data:image/svg")
                || value.contains(".svg");
    }

    private void refreshFlashButtonAppearance() {
        setFlashVisualState(torchEnabled);
    }

    private void applyFlashButtonVisualState(boolean isFlashActive) {
        styleButtonForState(flashButton, true, isFlashActive);
    }

    private void setFlashVisualState(boolean isFlashActive) {
        applyFlashButtonVisualState(isFlashActive);
        if (!hasSvg(true)) {
            flashButton.setText(getButtonLabel(true));
        }
        updateSvgButton(true, isFlashActive);
    }

    private int getSafeAreaTopPx() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
                if (insets != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        return insets.getInsets(WindowInsets.Type.statusBars()).top;
                    }
                    return insets.getSystemWindowInsetTop();
                }
            }
        } catch (Exception ignored) {
        }
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            return getResources().getDimensionPixelSize(resId);
        }
        return 0;
    }

    private int getSafeAreaBottomPx() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
                if (insets != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        return insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                    }
                    return insets.getSystemWindowInsetBottom();
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private void applyTopSafeAreaInsets() {
        int safeTopPx = getSafeAreaTopPx();
        if (safeTopPx == appliedSafeTopPx) {
            return;
        }
        appliedSafeTopPx = safeTopPx;
        applyTopMargin(barcodeView, safeTopPx);
        applyTopMargin(overlayView, safeTopPx);
        if (headerTextView != null) {
            applyTopMargin(headerTextView, safeTopPx);
        }
        if (loader != null) {
            loader.setTranslationY(safeTopPx / 2f);
        }
        if (flashButtonContainer != null && flashButtonContainer.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            applyBottomButtonLayout((FrameLayout.LayoutParams) flashButtonContainer.getLayoutParams(), true);
        }
        if (cancelButtonContainer != null && cancelButtonContainer.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            applyBottomButtonLayout((FrameLayout.LayoutParams) cancelButtonContainer.getLayoutParams(), false);
        }
    }

    private void applyTopMargin(View view, int topMarginPx) {
        if (view == null) {
            return;
        }
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        if (lp.topMargin == topMarginPx) {
            return;
        }
        lp.topMargin = topMarginPx;
        view.setLayoutParams(lp);
    }

    private void refreshCancelButtonAppearance() {
        boolean active = false;
        styleButtonForState(cancelButton, false, false);
        if (!hasSvg(false)) {
            cancelButton.setText(getButtonLabel(false));
        }
        updateSvgButton(false, active);
    }

    private boolean hasSvg(boolean isFlashButton) {
        return isIconMode() && isSvgValue(getButtonIconSource(isFlashButton));
    }

    private void attachSvgIconIfNeeded(boolean isFlashButton) {
        if (!hasSvg(isFlashButton)) {
            return;
        }
        FrameLayout container = isFlashButton ? flashButtonContainer : cancelButtonContainer;
        Button button = isFlashButton ? flashButton : cancelButton;
        WebView svgView = createSvgView();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        svgView.setLayoutParams(lp);
        container.addView(svgView);
        if (isFlashButton) {
            flashButtonSvgView = svgView;
            svgView.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        return true;
                    case MotionEvent.ACTION_UP:
                        flashButton.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return true;
                }
            });
        } else {
            cancelButtonSvgView = svgView;
            svgView.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        return true;
                    case MotionEvent.ACTION_UP:
                        cancelButton.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return true;
                }
            });
        }
        button.setText("");
    }

    private WebView createSvgView() {
        WebView webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        webView.setClickable(false);
        webView.setFocusable(false);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        return webView;
    }

    private void updateSvgButton(boolean isFlashButton, boolean active) {
        WebView svgView = isFlashButton ? flashButtonSvgView : cancelButtonSvgView;
        if (svgView == null) {
            return;
        }
        String svg = getButtonIconSource(isFlashButton);
        if (svg.isEmpty()) {
            return;
        }
        int tint = getButtonTextColor(isFlashButton, active);
        int iconPx = dp(Math.max(10, Math.round(getButtonContentSizeSp())));
        String tintCss = String.format("#%06X", 0xFFFFFF & tint);
        String body;
        if (svg.trim().startsWith("<svg")) {
            body = svg.replaceFirst("<svg", "<svg fill=\"currentColor\"");
        } else {
            body = "<img src=\"" + svg + "\" style=\"width:100%;height:100%;object-fit:contain;\"/>";
        }
        String html = "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>"
                + "<style>html,body{margin:0;padding:0;background:transparent;width:100%;height:100%;display:flex;align-items:center;justify-content:center;color:"
                + tintCss + ";}svg,img{width:" + iconPx + "px !important;height:" + iconPx + "px !important;max-width:90%;max-height:90%;}</style></head><body>" + body + "</body></html>";
        String encoded = Base64.encodeToString(html.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        svgView.loadData(encoded, "text/html; charset=utf-8", "base64");
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
        applyTopSafeAreaInsets();
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
        debugLog("onDestroy");
        if (barcodeView != null) {
            barcodeView.pause();
        }
        if (flashButtonSvgView != null) {
            flashButtonSvgView.destroy();
        }
        if (cancelButtonSvgView != null) {
            cancelButtonSvgView.destroy();
        }
        handler.removeCallbacksAndMessages(null);
        QrScannerPro.activeScannerActivity = new java.lang.ref.WeakReference<>(null);
        super.onDestroy();
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
