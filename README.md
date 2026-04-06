# Cordova QR Scanner Pro

Minimal, production-ready, highly customizable QR scanner plugin for Apache Cordova on **Android** and **iOS**.

> Package id: `cordova-plugin-qrscanner-pro`  
> JS namespace: `cordova.plugins.qrScannerPro`

## Why this plugin

- Native camera scanning on both platforms
- Fast, stable QR detection with clean UX defaults
- Full-screen customizable scanner UI (scan area, frame, overlay, loader, scan line, buttons)
- Straightforward API for app flows (`scan`, `cancel`, `toggleFlash`, `setFlash`)
- Security-friendly design (no network calls, local-only processing)

## Installation

### From npm (recommended)

```bash
cordova plugin add cordova-plugin-qrscanner-pro
```

### From Git

```bash
cordova plugin add https://github.com/pwndex/cordova-qrscanner-pro.git
```

### From local path

```bash
cordova plugin add /absolute/path/to/cordova-plugin-qrscanner-pro
```

## Platform support

| Platform | Supported | Notes |
|---|---|---|
| Android | Yes | Uses ZXing (JourneyApps embedded) |
| iOS | Yes | Uses AVFoundation |

## Quick start

```js
document.addEventListener("deviceready", function () {
  const qr = cordova.plugins.qrScannerPro;

  qr.scan(
    {
      scanZoneWidth: 280,
      scanZoneHeight: 280,
      darkenOutside: true,
      frameColor: "#00E5FF",
      loaderColor: "#00E5FF",
      loadingDelayMs: 850,
      showFlashButton: true,
      showCancelButton: true,
      animateScanLine: true
    },
    function onSuccess(result) {
      // result = { text, format, cancelled, rawBytesBase64? }
      console.log("QR:", result.text, result.format);
    },
    function onError(message) {
      // Called on cancel, permission denial, or runtime errors
      console.warn("Scan finished with error:", message);
    }
  );
});
```

## Platform-focused examples

Even though the JS API is the same on both platforms, production UX often differs by platform conventions.

### Android example (larger scan zone + explicit formats)

```js
document.addEventListener("deviceready", function () {
  const qr = cordova.plugins.qrScannerPro;

  function startAndroidScan() {
    qr.scan(
      {
        // Android-friendly defaults for mixed camera quality devices
        scanZoneWidth: 300,
        scanZoneHeight: 300,
        scanZoneOffsetY: -12,
        darkenOutside: true,
        overlayColor: "#99000000",

        frameColor: "#22D3EE",
        frameThickness: 4,
        frameCornerRadius: 14,

        showLoader: true,
        loaderColor: "#22D3EE",
        loadingDelayMs: 900,

        showFlashButton: true,
        flashButtonText: "Flash",
        showCancelButton: true,
        cancelButtonText: "Cancel",

        animateScanLine: true,
        scanLineColor: "#22D3EE",
        scanLineThickness: 3,
        scanLineDurationMs: 1700,

        hapticFeedback: true,
        returnRawBytesBase64: true,

        // Android only: helps constrain decoding and improve speed
        formats: ["QR_CODE", "AZTEC", "DATA_MATRIX"]
      },
      function onSuccess(result) {
        console.log("[Android] text:", result.text);
        console.log("[Android] format:", result.format);
        if (result.rawBytesBase64) {
          console.log("[Android] raw bytes present");
        }
      },
      function onError(message) {
        // Includes user cancel and runtime errors
        console.warn("[Android] scan ended:", message);
      }
    );
  }

  document.getElementById("scanBtn").addEventListener("click", startAndroidScan);
});
```

### iOS example (clean UI + strong cancel flow)

```js
document.addEventListener("deviceready", function () {
  const qr = cordova.plugins.qrScannerPro;

  function startIOSScan() {
    qr.scan(
      {
        // iOS-like centered clean layout
        scanZoneWidth: 260,
        scanZoneHeight: 260,
        scanZoneOffsetY: 0,
        darkenOutside: true,
        overlayColor: "#88000000",

        frameColor: "#34D399",
        frameThickness: 3,
        frameCornerRadius: 12,

        showLoader: true,
        loaderColor: "#34D399",
        loadingDelayMs: 750,

        showFlashButton: true,
        flashButtonText: "Torch",
        showCancelButton: true,
        cancelButtonText: "Close",

        buttonTextColor: "#FFFFFF",
        buttonBackgroundColor: "#66000000",

        animateScanLine: true,
        scanLineColor: "#34D399",
        scanLineThickness: 3,
        scanLineDurationMs: 1800,

        hapticFeedback: true
      },
      function onSuccess(result) {
        console.log("[iOS] scanned:", result.text);
      },
      function onError(message) {
        // Treat cancel as non-fatal branch
        if ((message || "").toLowerCase().includes("cancel")) {
          console.log("[iOS] user cancelled");
          return;
        }
        console.error("[iOS] scanning failed:", message);
      }
    );
  }

  document.getElementById("scanBtn").addEventListener("click", startIOSScan);
});
```

### Safe URL handling example (both platforms)

```js
function handleScannedText(text) {
  // Never trust scanned input directly
  let url;
  try {
    url = new URL(text);
  } catch (_) {
    console.log("Not a URL, treat as plain text:", text);
    return;
  }

  // Allowlist domains before opening externally
  const allowedHosts = new Set(["example.com", "app.example.com"]);
  if (!allowedHosts.has(url.hostname)) {
    console.warn("Blocked untrusted URL:", url.href);
    return;
  }

  // Open only trusted URLs
  cordova.InAppBrowser.open(url.href, "_system");
}
```

### Flash control example (during active session)

```js
const qr = cordova.plugins.qrScannerPro;

function enableFlashIfAvailable() {
  qr.isFlashAvailable(
    function (info) {
      if (info && info.available) {
        qr.setFlash(true, function () {
          console.log("Flash enabled");
        }, console.warn);
      }
    },
    console.warn
  );
}
```

## API reference

### `scan(options, onSuccess, onError)`

Starts a scanner session.

- **options**: object with scanner customization
- **onSuccess(result)**: called when a code is successfully parsed
- **onError(message)**: called on cancel/error/permission denial

### `cancel(onSuccess, onError)`

Cancels the active scanner session if present.

### `toggleFlash(onSuccess, onError)`

Toggles device torch during active session.

- **success payload**: `{ enabled: boolean }`

### `setFlash(enabled, onSuccess, onError)`

Explicitly sets torch state during active session.

- **enabled**: boolean
- **success payload**: `{ enabled: boolean }`

### `isFlashAvailable(onSuccess, onError)`

Checks torch availability on the current device.

- **success payload**: `{ available: boolean }`

## Result object

Successful `scan` callback returns:

```ts
type ScanResult = {
  text: string;              // Decoded QR content
  format: string;            // QR_CODE | AZTEC | DATA_MATRIX | ...
  cancelled: false;
  rawBytesBase64?: string;   // Android only, if returnRawBytesBase64=true
};
```

## Configuration options

All options are optional. Defaults are safe for production.

| Option | Type | Default | Description |
|---|---|---|---|
| `scanZoneWidth` | number | `260` | Scan area width (dp/pt) |
| `scanZoneHeight` | number | `260` | Scan area height (dp/pt) |
| `scanZoneOffsetY` | number | `0` | Vertical offset for scan area |
| `restrictScanToZone` | boolean | `false` | Accept detection only when code center is inside scan zone |
| `darkenOutside` | boolean | `true` | Darken area outside scan zone |
| `overlayColor` | string | `#88000000` | Overlay color |
| `frameColor` | string | `#00E676` | Scan frame color |
| `frameThickness` | number | `3` | Frame stroke thickness |
| `frameCornerRadius` | number | `12` | Frame corner radius |
| `showLoader` | boolean | `true` | Show loader before returning result |
| `loaderColor` | string | `#00E676` | Loader color |
| `loadingDelayMs` | number | `700` | Delay before success callback |
| `showFlashButton` | boolean | `true` | Show Flash button |
| `flashButtonText` | string | `Flash` | Flash button label |
| `flashButtonIcon` | string | `⚡` | Flash icon in `buttonMode: "icon"` |
| `showCancelButton` | boolean | `true` | Show Cancel button |
| `cancelButtonText` | string | `Cancel` | Cancel button label |
| `cancelButtonIcon` | string | `✕` | Cancel icon in `buttonMode: "icon"` |
| `buttonMode` | string | `text` | `text` or `icon` |
| `buttonSize` | number | `52` | Button height; icon mode uses square |
| `buttonTextWidth` | number | `110` | Text mode button width |
| `buttonCornerRadius` | number | `10` | Button corner radius |
| `buttonSpacing` | number | `16` | Space between flash/cancel buttons |
| `buttonBottomOffset` | number | `46` | Distance from bottom edge |
| `buttonTextColor` | string | `#FFFFFFFF` | Button text color |
| `buttonBackgroundColor` | string | `#66000000` | Button background color |
| `animateScanLine` | boolean | `true` | Enable animated scan line |
| `scanLineColor` | string | `#00E676` | Scan line color |
| `scanLineThickness` | number | `3` | Scan line thickness |
| `scanLineDurationMs` | number | `1800` | Full up/down animation duration |
| `debug` | boolean | `false` | Enable native debug logs for scan lifecycle |
| `debugTag` | string | `QrScannerPro` | Prefix tag for native logs |
| `hapticFeedback` | boolean | `true` | Vibrate/beep on success |
| `returnRawBytesBase64` | boolean | `false` | Return raw bytes (Android only) |
| `formats` | string[] | QR-focused defaults | Custom ZXing formats (Android only) |

### Debug mode

Use debug mode when scanner lifecycle events need troubleshooting.

```js
cordova.plugins.qrScannerPro.scan(
  {
    debug: true,
    debugTag: "QrScannerPro",
    showCancelButton: true,
    showFlashButton: true
  },
  onSuccess,
  onError
);
```

Native logs will include events for:
- scanner open/present
- camera permission status
- detection callback
- flash toggle/set state
- cancel button / cancel from JS
- scanner close with success/error

### Rounded scan-zone cutout and icon buttons

Use these options to keep overlay cutout and frame visually consistent, and render circular icon buttons:

```js
{
  frameCornerRadius: 14,
  overlayColor: "#CC000000",
  buttonMode: "icon",
  buttonSize: 54,
  buttonCornerRadius: 27,
  buttonSpacing: 14,
  buttonBottomOffset: 56,
  buttonTextColor: "#FFFFFF",
  buttonBackgroundColor: "#A61E1E1E",
  flashButtonIcon: "⚡",
  cancelButtonIcon: "✕"
}
```

## Production recommendations

- Keep `loadingDelayMs` between `600-1200` ms for smoother UX
- Use larger scan area for dense or damaged QR codes
- Handle permission denial with clear UI fallback in your app
- Validate QR payload before navigation or API calls
- Add app-level rate limiting if users can scan repeatedly

## Security notes

- No remote calls are made by the plugin
- Scanning is fully local on device
- Treat scanned data as untrusted input
- Use allowlists for URL schemes/domains before opening links

## Troubleshooting

### Camera permission denied

- Ensure camera permission is requested by OS
- Verify `NSCameraUsageDescription` is present in iOS app plist
- Ask user to re-enable permission in system settings

### Flash button does nothing

- Not all devices have torch hardware
- Call `isFlashAvailable()` before enabling flash UX

### Cancel returns error callback

- This is expected behavior in current API design
- Handle cancel as a non-fatal branch in `onError`

## Development and release checklist

- Verify scanning on at least 2 physical Android devices
- Verify scanning on at least 2 physical iOS devices
- Test low light, glare, small QR, and rotated QR cases
- Test permission denied and permission revoked flows
- Pin dependency versions before public release
- Tag release and keep `CHANGELOG.md` per version

## License

MIT
