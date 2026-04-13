var exec = require("cordova/exec");

function ensureObject(input) {
  if (!input || typeof input !== "object") {
    return {};
  }
  return input;
}

function QrScannerPro() {}

QrScannerPro.prototype.scan = function scan(options, success, error) {
  exec(success, error, "QrScannerPro", "scan", [ensureObject(options)]);
};

QrScannerPro.prototype.cancel = function cancel(success, error) {
  exec(success, error, "QrScannerPro", "cancel", []);
};

QrScannerPro.prototype.toggleFlash = function toggleFlash(success, error) {
  exec(success, error, "QrScannerPro", "toggleFlash", []);
};

QrScannerPro.prototype.setFlash = function setFlash(enabled, success, error) {
  exec(success, error, "QrScannerPro", "setFlash", [!!enabled]);
};

QrScannerPro.prototype.isFlashAvailable = function isFlashAvailable(success, error) {
  exec(success, error, "QrScannerPro", "isFlashAvailable", []);
};

QrScannerPro.prototype.getDefaults = function getDefaults() {
  return {
    scanZoneWidth: 260,
    scanZoneHeight: 260,
    scanZoneOffsetY: 0,
    frameColor: "#00E676",
    frameThickness: 3,
    frameCornerRadius: 12,
    darkenOutside: true,
    overlayColor: "#88000000",
    showLoader: true,
    loaderColor: "#00E676",
    loadingDelayMs: 700,
    showFlashButton: true,
    showCancelButton: true,
    headerText: "",
    headerHeight: 56,
    headerPadding: 12,
    headerBackgroundColor: "#00000000",
    headerTextColor: "#FFFFFFFF",
    headerFontSize: 18,
    buttonMode: "text",
    buttonSize: 52,
    buttonCornerRadius: 10,
    buttonSpacing: 16,
    buttonBottomOffset: 46,
    buttonTextWidth: 110,
    flashButtonText: "Flash",
    cancelButtonText: "Cancel",
    flashButtonIcon: "⚡",
    cancelButtonIcon: "✕",
    flashButtonSvg: "",
    flashButtonActiveSvg: "",
    cancelButtonSvg: "",
    cancelButtonActiveSvg: "",
    buttonTextColor: "#FFFFFFFF",
    buttonBackgroundColor: "#66000000",
    buttonActiveTextColor: "#FFFFFFFF",
    buttonActiveBackgroundColor: "#AA000000",
    flashButtonTextColor: "",
    flashButtonBackgroundColor: "",
    flashButtonActiveTextColor: "",
    flashButtonActiveBackgroundColor: "",
    cancelButtonTextColor: "",
    cancelButtonBackgroundColor: "",
    cancelButtonActiveTextColor: "",
    cancelButtonActiveBackgroundColor: "",
    animateScanLine: true,
    scanLineColor: "#00E676",
    scanLineThickness: 3,
    scanLineDurationMs: 1800,
    restrictScanToZone: false,
    debug: false,
    debugTag: "QrScannerPro",
    hapticFeedback: true,
    returnRawBytesBase64: false
  };
};

module.exports = new QrScannerPro();
