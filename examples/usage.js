document.addEventListener("deviceready", function () {
  var scanner = cordova.plugins.qrScannerPro;

  document.getElementById("scanBtn").addEventListener("click", function () {
    scanner.scan(
      {
        scanZoneWidth: 280,
        scanZoneHeight: 280,
        darkenOutside: true,
        frameColor: "#00E5FF",
        loaderColor: "#00E5FF",
        loadingDelayMs: 800,
        showFlashButton: true,
        showCancelButton: true,
        animateScanLine: true
      },
      function (result) {
        console.log("Scanned:", result.text, result.format);
      },
      function (message) {
        console.warn("Scan error:", message);
      }
    );
  });
});
