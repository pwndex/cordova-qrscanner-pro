#import <Cordova/CDV.h>
#import <AVFoundation/AVFoundation.h>
#import "QRScannerViewController.h"

@interface QrScannerPro : CDVPlugin
@property (nonatomic, strong) QRScannerViewController *scannerVC;
@property (nonatomic, copy) NSString *scanCallbackId;
@property (nonatomic, assign) BOOL debugEnabled;
@property (nonatomic, copy) NSString *debugTag;
@end

@implementation QrScannerPro

- (void)debugLog:(NSString *)message {
    if (!self.debugEnabled) {
        return;
    }
    NSString *tag = self.debugTag.length > 0 ? self.debugTag : @"QrScannerPro-iOS";
    NSLog(@"[%@] %@", tag, message ?: @"");
}

- (void)scan:(CDVInvokedUrlCommand *)command {
    if (self.scannerVC != nil) {
        CDVPluginResult *busy = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"A scan session is already active."];
        [self.commandDelegate sendPluginResult:busy callbackId:command.callbackId];
        return;
    }

    NSDictionary *options = @{};
    if (command.arguments.count > 0 && [command.arguments[0] isKindOfClass:[NSDictionary class]]) {
        options = command.arguments[0];
    }
    self.debugEnabled = [options[@"debug"] respondsToSelector:@selector(boolValue)] ? [options[@"debug"] boolValue] : NO;
    self.debugTag = [options[@"debugTag"] isKindOfClass:[NSString class]] ? options[@"debugTag"] : @"QrScannerPro-iOS";
    [self debugLog:@"scan called"];

    self.scanCallbackId = command.callbackId;
    self.scannerVC = [[QRScannerViewController alloc] initWithOptions:options];

    __weak QrScannerPro *weakSelf = self;
    self.scannerVC.onSuccess = ^(NSDictionary *result) {
        QrScannerPro *strongSelf = weakSelf;
        if (!strongSelf) {
            return;
        }
        [strongSelf dismissAndSendSuccess:result];
    };
    self.scannerVC.onCancel = ^(NSString *message) {
        QrScannerPro *strongSelf = weakSelf;
        if (!strongSelf) {
            return;
        }
        [strongSelf dismissAndSendError:message ?: @"Scan cancelled."];
    };

    dispatch_async(dispatch_get_main_queue(), ^{
        [self debugLog:@"present scanner VC"];
        [self.viewController presentViewController:self.scannerVC animated:YES completion:nil];
    });
}

- (void)cancel:(CDVInvokedUrlCommand *)command {
    [self debugLog:@"cancel action called from JS"];
    dispatch_async(dispatch_get_main_queue(), ^{
        if (self.scannerVC) {
            [self.scannerVC cancelFromPlugin];
        }
        CDVPluginResult *ok = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
        [self.commandDelegate sendPluginResult:ok callbackId:command.callbackId];
    });
}

- (void)toggleFlash:(CDVInvokedUrlCommand *)command {
    [self debugLog:@"toggleFlash action called from JS"];
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!self.scannerVC) {
            CDVPluginResult *err = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"No active scan session."];
            [self.commandDelegate sendPluginResult:err callbackId:command.callbackId];
            return;
        }
        BOOL enabled = [self.scannerVC toggleTorch];
        NSDictionary *payload = @{@"enabled": @(enabled)};
        CDVPluginResult *ok = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:payload];
        [self.commandDelegate sendPluginResult:ok callbackId:command.callbackId];
    });
}

- (void)setFlash:(CDVInvokedUrlCommand *)command {
    [self debugLog:@"setFlash action called from JS"];
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!self.scannerVC) {
            CDVPluginResult *err = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"No active scan session."];
            [self.commandDelegate sendPluginResult:err callbackId:command.callbackId];
            return;
        }
        BOOL enabled = NO;
        if (command.arguments.count > 0 && [command.arguments[0] respondsToSelector:@selector(boolValue)]) {
            enabled = [command.arguments[0] boolValue];
        }
        [self.scannerVC setTorchEnabled:enabled];
        NSDictionary *payload = @{@"enabled": @([self.scannerVC torchEnabledState])};
        CDVPluginResult *ok = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:payload];
        [self.commandDelegate sendPluginResult:ok callbackId:command.callbackId];
    });
}

- (void)isFlashAvailable:(CDVInvokedUrlCommand *)command {
    BOOL available = self.scannerVC ? [self.scannerVC isTorchAvailable] : [[AVCaptureDevice defaultDeviceWithMediaType:AVMediaTypeVideo] hasTorch];
    NSDictionary *payload = @{@"available": @(available)};
    CDVPluginResult *ok = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:payload];
    [self.commandDelegate sendPluginResult:ok callbackId:command.callbackId];
}

- (void)dismissAndSendSuccess:(NSDictionary *)result {
    if (!self.scannerVC) {
        return;
    }
    NSString *callbackId = self.scanCallbackId;
    UIViewController *presented = self.scannerVC;
    self.scannerVC = nil;
    self.scanCallbackId = nil;

    [presented dismissViewControllerAnimated:YES completion:^{
        [self debugLog:@"dismissAndSendSuccess"];
        CDVPluginResult *ok = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:result];
        [self.commandDelegate sendPluginResult:ok callbackId:callbackId];
    }];
}

- (void)dismissAndSendError:(NSString *)message {
    if (!self.scannerVC) {
        return;
    }
    NSString *callbackId = self.scanCallbackId;
    UIViewController *presented = self.scannerVC;
    self.scannerVC = nil;
    self.scanCallbackId = nil;

    [presented dismissViewControllerAnimated:YES completion:^{
        [self debugLog:[NSString stringWithFormat:@"dismissAndSendError: %@", message ?: @""]];
        CDVPluginResult *err = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:message ?: @"Scan cancelled."];
        [self.commandDelegate sendPluginResult:err callbackId:callbackId];
    }];
}

@end
