#import "QRScannerViewController.h"
#import <AVFoundation/AVFoundation.h>
#import <AudioToolbox/AudioToolbox.h>
#import <math.h>
#import <Vision/Vision.h>
#import <ImageIO/ImageIO.h>
#import <WebKit/WebKit.h>

@interface QROverlayView : UIView
@property (nonatomic, strong) NSDictionary *options;
@property (nonatomic, assign) CGRect scanRect;
@property (nonatomic, assign) CGFloat lineProgress;
@property (nonatomic, strong) CADisplayLink *displayLink;
@property (nonatomic, assign) CFTimeInterval animationStart;
@end

@implementation QROverlayView

- (instancetype)initWithFrame:(CGRect)frame options:(NSDictionary *)options {
    self = [super initWithFrame:frame];
    if (self) {
        _options = options ?: @{};
        _lineProgress = 0;
        self.backgroundColor = [UIColor clearColor];
    }
    return self;
}

- (void)startAnimation {
    BOOL animate = [self.options[@"animateScanLine"] respondsToSelector:@selector(boolValue)] ? [self.options[@"animateScanLine"] boolValue] : YES;
    if (!animate) {
        return;
    }
    [self.displayLink invalidate];
    self.animationStart = CACurrentMediaTime();
    self.displayLink = [CADisplayLink displayLinkWithTarget:self selector:@selector(tick:)];
    [self.displayLink addToRunLoop:[NSRunLoop mainRunLoop] forMode:NSRunLoopCommonModes];
}

- (void)tick:(CADisplayLink *)link {
    CGFloat durationMs = [self.options[@"scanLineDurationMs"] respondsToSelector:@selector(floatValue)] ? [self.options[@"scanLineDurationMs"] floatValue] : 1800.0f;
    CGFloat duration = MAX(0.6f, durationMs / 1000.0f);
    CFTimeInterval t = CACurrentMediaTime() - self.animationStart;
    CGFloat phase = fmod(t, duration * 2.0f) / duration;
    if (phase > 1.0f) {
        phase = 2.0f - phase;
    }
    self.lineProgress = phase;
    [self setNeedsDisplay];
}

- (UIColor *)colorFromHex:(NSString *)hex fallback:(UIColor *)fallback {
    if (![hex isKindOfClass:[NSString class]] || hex.length == 0) {
        return fallback;
    }
    NSString *clean = [[hex stringByReplacingOccurrencesOfString:@"#" withString:@""] uppercaseString];
    unsigned int value = 0;
    if (![[NSScanner scannerWithString:clean] scanHexInt:&value]) {
        return fallback;
    }
    if (clean.length == 6) {
        CGFloat r = ((value >> 16) & 0xFF) / 255.0;
        CGFloat g = ((value >> 8) & 0xFF) / 255.0;
        CGFloat b = (value & 0xFF) / 255.0;
        return [UIColor colorWithRed:r green:g blue:b alpha:1.0];
    }
    if (clean.length == 8) {
        CGFloat a = ((value >> 24) & 0xFF) / 255.0;
        CGFloat r = ((value >> 16) & 0xFF) / 255.0;
        CGFloat g = ((value >> 8) & 0xFF) / 255.0;
        CGFloat b = (value & 0xFF) / 255.0;
        return [UIColor colorWithRed:r green:g blue:b alpha:a];
    }
    return fallback;
}

- (void)drawRect:(CGRect)rect {
    [super drawRect:rect];
    CGContextRef ctx = UIGraphicsGetCurrentContext();
    if (!ctx) {
        return;
    }

    UIColor *overlay = [self colorFromHex:self.options[@"overlayColor"] fallback:[UIColor colorWithWhite:0 alpha:0.55]];
    UIColor *frame = [self colorFromHex:self.options[@"frameColor"] fallback:[UIColor colorWithRed:0 green:0.9 blue:0.45 alpha:1]];
    UIColor *line = [self colorFromHex:self.options[@"scanLineColor"] fallback:[UIColor colorWithRed:0 green:0.9 blue:0.45 alpha:1]];
    BOOL darken = [self.options[@"darkenOutside"] respondsToSelector:@selector(boolValue)] ? [self.options[@"darkenOutside"] boolValue] : YES;
    CGFloat frameThickness = MAX(1.0, [self.options[@"frameThickness"] respondsToSelector:@selector(floatValue)] ? [self.options[@"frameThickness"] floatValue] : 3.0);
    CGFloat lineThickness = MAX(1.0, [self.options[@"scanLineThickness"] respondsToSelector:@selector(floatValue)] ? [self.options[@"scanLineThickness"] floatValue] : 3.0);
    CGFloat radius = MAX(0.0, [self.options[@"frameCornerRadius"] respondsToSelector:@selector(floatValue)] ? [self.options[@"frameCornerRadius"] floatValue] : 12.0);
    BOOL animate = [self.options[@"animateScanLine"] respondsToSelector:@selector(boolValue)] ? [self.options[@"animateScanLine"] boolValue] : YES;

    if (darken) {
        UIBezierPath *overlayPath = [UIBezierPath bezierPathWithRect:self.bounds];
        UIBezierPath *holePath = [UIBezierPath bezierPathWithRoundedRect:self.scanRect cornerRadius:radius];
        [overlayPath appendPath:holePath];
        overlayPath.usesEvenOddFillRule = YES;
        [overlay setFill];
        [overlayPath fill];
    }

    UIBezierPath *framePath = [UIBezierPath bezierPathWithRoundedRect:self.scanRect cornerRadius:radius];
    framePath.lineWidth = frameThickness;
    [frame setStroke];
    [framePath stroke];

    if (animate) {
        CGFloat y = self.scanRect.origin.y + self.scanRect.size.height * self.lineProgress;
        CGRect lineRect = CGRectMake(self.scanRect.origin.x + frameThickness, y, self.scanRect.size.width - frameThickness * 2, lineThickness);
        CGContextSetFillColorWithColor(ctx, line.CGColor);
        CGContextFillRect(ctx, lineRect);
    }
}

- (void)dealloc {
    [self.displayLink invalidate];
}

@end

@interface QRScannerViewController () <AVCaptureMetadataOutputObjectsDelegate, AVCaptureVideoDataOutputSampleBufferDelegate>
@property (nonatomic, strong) NSDictionary *options;
@property (nonatomic, strong) AVCaptureSession *session;
@property (nonatomic, strong) AVCaptureVideoPreviewLayer *previewLayer;
@property (nonatomic, strong) AVCaptureDeviceInput *videoInput;
@property (nonatomic, strong) AVCaptureMetadataOutput *metadataOutput;
@property (nonatomic, strong) AVCaptureVideoDataOutput *videoDataOutput;
@property (nonatomic, strong) UIActivityIndicatorView *loader;
@property (nonatomic, strong) UIButton *flashButton;
@property (nonatomic, strong) UIButton *cancelButton;
@property (nonatomic, strong) WKWebView *flashSvgView;
@property (nonatomic, strong) WKWebView *cancelSvgView;
@property (nonatomic, strong) UIView *headerView;
@property (nonatomic, strong) UILabel *headerLabel;
@property (nonatomic, strong) QROverlayView *overlayView;
@property (nonatomic, assign) BOOL resultLocked;
@property (nonatomic, assign) BOOL torchEnabled;
@property (nonatomic, assign) BOOL torchBusy;
@property (nonatomic, strong) dispatch_queue_t captureQueue;
@property (nonatomic, strong) dispatch_queue_t metadataQueue;
@property (nonatomic, strong) dispatch_queue_t visionQueue;
@property (nonatomic, assign) CFTimeInterval lastVisionScanTime;
@property (nonatomic, assign) CGImagePropertyOrientation visionOrientation;
@end

@implementation QRScannerViewController

- (instancetype)initWithOptions:(NSDictionary *)options {
    self = [super init];
    if (self) {
        _options = options ?: @{};
        _resultLocked = NO;
        _torchEnabled = NO;
        _captureQueue = dispatch_queue_create("com.qrscannerpro.ios.capture", DISPATCH_QUEUE_SERIAL);
        _metadataQueue = dispatch_queue_create("com.qrscannerpro.ios.metadata", DISPATCH_QUEUE_SERIAL);
        _visionQueue = dispatch_queue_create("com.qrscannerpro.ios.vision", DISPATCH_QUEUE_SERIAL);
        _lastVisionScanTime = 0;
        _visionOrientation = kCGImagePropertyOrientationRight;
        self.modalPresentationStyle = UIModalPresentationFullScreen;
    }
    return self;
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor blackColor];
    [self buildUI];
    [self setupCamera];
}

- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];
    CGRect contentFrame = [self scannerContentFrame];
    self.previewLayer.frame = contentFrame;
    self.overlayView.frame = contentFrame;
    [self updateScanRect];
    [self layoutHeader];
    [self applyBottomButtonLayout:self.flashButton isFlash:YES];
    [self applyBottomButtonLayout:self.cancelButton isFlash:NO];
    self.loader.center = CGPointMake(CGRectGetMidX(contentFrame), CGRectGetMidY(contentFrame));
    self.flashSvgView.frame = self.flashButton.bounds;
    self.cancelSvgView.frame = self.cancelButton.bounds;
}

- (UIColor *)colorFromHex:(NSString *)hex fallback:(UIColor *)fallback {
    if (![hex isKindOfClass:[NSString class]] || hex.length == 0) {
        return fallback;
    }
    NSString *clean = [[hex stringByReplacingOccurrencesOfString:@"#" withString:@""] uppercaseString];
    unsigned int value = 0;
    if (![[NSScanner scannerWithString:clean] scanHexInt:&value]) {
        return fallback;
    }
    if (clean.length == 6) {
        return [UIColor colorWithRed:((value >> 16) & 0xFF) / 255.0
                               green:((value >> 8) & 0xFF) / 255.0
                                blue:(value & 0xFF) / 255.0
                               alpha:1.0];
    }
    if (clean.length == 8) {
        return [UIColor colorWithRed:((value >> 16) & 0xFF) / 255.0
                               green:((value >> 8) & 0xFF) / 255.0
                                blue:(value & 0xFF) / 255.0
                               alpha:((value >> 24) & 0xFF) / 255.0];
    }
    return fallback;
}

- (CGFloat)optFloat:(NSString *)key defaultValue:(CGFloat)defaultValue {
    id value = self.options[key];
    if ([value respondsToSelector:@selector(floatValue)]) {
        return [value floatValue];
    }
    return defaultValue;
}

- (BOOL)optBool:(NSString *)key defaultValue:(BOOL)defaultValue {
    id value = self.options[key];
    if ([value respondsToSelector:@selector(boolValue)]) {
        return [value boolValue];
    }
    return defaultValue;
}

- (NSString *)optString:(NSString *)key defaultValue:(NSString *)defaultValue {
    id value = self.options[key];
    if ([value isKindOfClass:[NSString class]] && ((NSString *)value).length > 0) {
        return value;
    }
    return defaultValue;
}

- (BOOL)isDebugEnabled {
    id value = self.options[@"debug"];
    return [value respondsToSelector:@selector(boolValue)] ? [value boolValue] : NO;
}

- (NSString *)debugTag {
    id tag = self.options[@"debugTag"];
    if ([tag isKindOfClass:[NSString class]] && ((NSString *)tag).length > 0) {
        return (NSString *)tag;
    }
    return @"QrScannerPro-iOS";
}

- (void)debugLog:(NSString *)message {
    if (![self isDebugEnabled]) {
        return;
    }
    NSLog(@"[%@] %@", [self debugTag], message ?: @"");
}

- (void)buildUI {
    self.overlayView = [[QROverlayView alloc] initWithFrame:self.view.bounds options:self.options];
    self.overlayView.userInteractionEnabled = NO;
    self.overlayView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    [self.view addSubview:self.overlayView];

    [self buildHeader];

    UIActivityIndicatorViewStyle loaderStyle = UIActivityIndicatorViewStyleWhiteLarge;
    if (@available(iOS 13.0, *)) {
        loaderStyle = UIActivityIndicatorViewStyleLarge;
    }
    self.loader = [[UIActivityIndicatorView alloc] initWithActivityIndicatorStyle:loaderStyle];
    self.loader.color = [self colorFromHex:[self optString:@"loaderColor" defaultValue:@"#00E676"]
                                  fallback:[UIColor colorWithRed:0 green:0.9 blue:0.45 alpha:1]];
    self.loader.center = self.view.center;
    self.loader.hidesWhenStopped = YES;
    self.loader.autoresizingMask = UIViewAutoresizingFlexibleLeftMargin
                                 | UIViewAutoresizingFlexibleRightMargin
                                 | UIViewAutoresizingFlexibleTopMargin
                                 | UIViewAutoresizingFlexibleBottomMargin;
    [self.view addSubview:self.loader];

    self.flashButton = [UIButton buttonWithType:UIButtonTypeCustom];
    [self styleButton:self.flashButton isFlash:YES active:NO];
    [self applyBottomButtonLayout:self.flashButton isFlash:YES];
    [self.flashButton addTarget:self action:@selector(onFlashTap) forControlEvents:UIControlEventTouchUpInside];
    self.flashButton.hidden = ![self optBool:@"showFlashButton" defaultValue:YES];
    [self.view addSubview:self.flashButton];
    [self attachSvgIfNeeded:YES];

    self.cancelButton = [UIButton buttonWithType:UIButtonTypeCustom];
    [self styleButton:self.cancelButton isFlash:NO active:NO];
    [self applyBottomButtonLayout:self.cancelButton isFlash:NO];
    [self.cancelButton addTarget:self action:@selector(onCancelTap) forControlEvents:UIControlEventTouchUpInside];
    self.cancelButton.hidden = ![self optBool:@"showCancelButton" defaultValue:YES];
    [self.view addSubview:self.cancelButton];
    [self attachSvgIfNeeded:NO];
    [self refreshFlashButtonAppearance];
    [self refreshCancelButtonAppearance];
}

- (void)buildHeader {
    NSString *title = [[self optString:@"headerText" defaultValue:@""] stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
    if (title.length == 0) {
        return;
    }
    self.headerView = [[UIView alloc] initWithFrame:CGRectZero];
    self.headerView.backgroundColor = [self colorFromHex:[self optString:@"headerBackgroundColor" defaultValue:@"#00000000"]
                                                fallback:[UIColor clearColor]];
    [self.view addSubview:self.headerView];

    self.headerLabel = [[UILabel alloc] initWithFrame:CGRectZero];
    self.headerLabel.textAlignment = NSTextAlignmentCenter;
    self.headerLabel.text = title;
    self.headerLabel.textColor = [self colorFromHex:[self optString:@"headerTextColor" defaultValue:@"#FFFFFFFF"] fallback:[UIColor whiteColor]];
    CGFloat size = MAX(12.0, [self optFloat:@"headerFontSize" defaultValue:18.0]);
    UIFont *font = [UIFont fontWithName:@"Satoshi-Regular" size:size];
    if (!font) {
        font = [UIFont systemFontOfSize:size weight:UIFontWeightSemibold];
    }
    self.headerLabel.font = font;
    [self.headerView addSubview:self.headerLabel];
    [self layoutHeader];
}

- (void)layoutHeader {
    if (!self.headerView) {
        return;
    }
    CGFloat height = MAX(32.0, [self optFloat:@"headerHeight" defaultValue:56.0]);
    CGFloat padding = MAX(0.0, [self optFloat:@"headerPadding" defaultValue:12.0]);
    CGFloat safeTop = self.view.safeAreaInsets.top;
    self.headerView.frame = CGRectMake(0, safeTop, self.view.bounds.size.width, height);
    CGFloat labelX = padding;
    CGFloat labelY = padding;
    CGFloat labelW = MAX(0.0, self.headerView.bounds.size.width - padding * 2.0);
    CGFloat labelH = MAX(0.0, height - padding * 2.0);
    self.headerLabel.frame = CGRectMake(labelX, labelY, labelW, labelH);
}

- (CGRect)scannerContentFrame {
    CGFloat safeTop = self.view.safeAreaInsets.top;
    CGRect frame = self.view.bounds;
    frame.origin.y = safeTop;
    frame.size.height = MAX(0.0, frame.size.height - safeTop);
    return frame;
}

- (BOOL)isIconMode {
    return [[self optString:@"buttonMode" defaultValue:@"text"] isEqualToString:@"icon"];
}

- (NSString *)buttonLabelForFlash {
    if ([self isIconMode]) {
        return [self optString:@"flashButtonIcon" defaultValue:@"⚡"];
    }
    NSString *base = [self optString:@"flashButtonText" defaultValue:@"Flash"];
    return self.torchEnabled ? [base stringByAppendingString:@" ON"] : base;
}

- (NSString *)buttonLabelForCancel {
    if ([self isIconMode]) {
        return [self optString:@"cancelButtonIcon" defaultValue:@"✕"];
    }
    return [self optString:@"cancelButtonText" defaultValue:@"Cancel"];
}

- (UIColor *)buttonTextColorIsFlash:(BOOL)isFlash active:(BOOL)active {
    NSString *globalColor = [self optString:@"buttonTextColor" defaultValue:@"#FFFFFFFF"];
    NSString *buttonKey;
    if (isFlash) {
        buttonKey = active ? @"flashButtonActiveTextColor" : @"flashButtonTextColor";
    } else {
        buttonKey = @"cancelButtonTextColor";
    }
    NSString *override = [self optString:buttonKey defaultValue:@""];
    NSString *resolved = override.length > 0 ? override : globalColor;
    return [self colorFromHex:resolved fallback:[UIColor whiteColor]];
}

- (UIColor *)buttonBackgroundColorIsFlash:(BOOL)isFlash active:(BOOL)active {
    NSString *globalColor = [self optString:@"buttonBackgroundColor" defaultValue:@"#66000000"];
    NSString *buttonKey;
    if (isFlash) {
        buttonKey = active ? @"flashButtonActiveBackgroundColor" : @"flashButtonBackgroundColor";
    } else {
        buttonKey = @"cancelButtonBackgroundColor";
    }
    NSString *override = [self optString:buttonKey defaultValue:@""];
    NSString *resolved = override.length > 0 ? override : globalColor;
    return [self colorFromHex:resolved fallback:[UIColor colorWithWhite:0 alpha:(active ? 0.67 : 0.4)]];
}

- (void)styleButton:(UIButton *)button isFlash:(BOOL)isFlash active:(BOOL)active {
    NSString *title = [self hasSvgForButton:isFlash] ? @"" : (isFlash ? [self buttonLabelForFlash] : [self buttonLabelForCancel]);
    [button setTitle:title forState:UIControlStateNormal];
    [button setTitle:title forState:UIControlStateHighlighted];
    [button setTitle:title forState:UIControlStateSelected];
    BOOL iconMode = [self isIconMode];
    UIColor *titleColor = [self buttonTextColorIsFlash:isFlash active:active];
    [button setTitleColor:titleColor forState:UIControlStateNormal];
    [button setTitleColor:titleColor forState:UIControlStateHighlighted];
    [button setTitleColor:titleColor forState:UIControlStateSelected];
    button.backgroundColor = [self buttonBackgroundColorIsFlash:isFlash active:active];
    CGFloat size = MAX(36.0, [self optFloat:@"buttonSize" defaultValue:52]);
    CGFloat radius = MAX(0.0, [self optFloat:@"buttonCornerRadius" defaultValue:(iconMode ? (size / 2.0) : 10.0)]);
    button.layer.cornerRadius = radius;
    button.clipsToBounds = YES;
    button.titleLabel.font = [UIFont systemFontOfSize:[self buttonContentSize] weight:UIFontWeightSemibold];
}

- (CGFloat)buttonContentSize {
    BOOL iconMode = [self isIconMode];
    CGFloat fallback = iconMode ? 22.0 : 15.0;
    return MAX(10.0, [self optFloat:@"buttonContentSize" defaultValue:fallback]);
}

- (void)applyBottomButtonLayout:(UIButton *)button isFlash:(BOOL)isFlash {
    BOOL iconMode = [self isIconMode];
    CGFloat buttonHeight = MAX(32.0, [self optFloat:@"buttonHeight" defaultValue:[self optFloat:@"buttonSize" defaultValue:52]]);
    CGFloat buttonWidth = iconMode
        ? MAX(32.0, [self optFloat:@"buttonSize" defaultValue:52])
        : MAX(64.0, [self optFloat:@"buttonWidth" defaultValue:110]);
    CGFloat spacing = MAX(8.0, [self optFloat:@"buttonSpacing" defaultValue:16]);
    CGFloat bottomOffset = MAX(8.0, [self optFloat:@"buttonBottomOffset" defaultValue:46]);
    CGFloat halfGap = spacing / 2.0 + buttonWidth / 2.0;
    CGRect contentFrame = [self scannerContentFrame];
    CGFloat centerX = CGRectGetMidX(contentFrame) + (isFlash ? -halfGap : halfGap);
    CGFloat originX = centerX - buttonWidth / 2.0;
    CGFloat originY = CGRectGetMaxY(contentFrame) - bottomOffset - buttonHeight - self.view.safeAreaInsets.bottom;

    button.frame = CGRectMake(originX, originY, buttonWidth, buttonHeight);
    button.autoresizingMask = UIViewAutoresizingFlexibleTopMargin | UIViewAutoresizingFlexibleLeftMargin | UIViewAutoresizingFlexibleRightMargin;
}

- (NSString *)buttonIconValue:(BOOL)isFlash {
    return [self optString:(isFlash ? @"flashButtonIcon" : @"cancelButtonIcon") defaultValue:(isFlash ? @"⚡" : @"✕")];
}

- (BOOL)isSvgValue:(NSString *)value {
    NSString *trimmed = [[value ?: @"" stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]] lowercaseString];
    return [trimmed hasPrefix:@"<svg"] || [trimmed hasPrefix:@"data:image/svg"] || [trimmed containsString:@".svg"];
}

- (BOOL)hasSvgForButton:(BOOL)isFlash {
    return [self isIconMode] && [self isSvgValue:[self buttonIconValue:isFlash]];
}

- (void)attachSvgIfNeeded:(BOOL)isFlash {
    if (![self hasSvgForButton:isFlash]) {
        return;
    }
    WKWebViewConfiguration *config = [[WKWebViewConfiguration alloc] init];
    WKWebView *web = [[WKWebView alloc] initWithFrame:CGRectZero configuration:config];
    web.backgroundColor = [UIColor clearColor];
    web.opaque = NO;
    web.userInteractionEnabled = NO;
    web.scrollView.scrollEnabled = NO;
    UIButton *button = isFlash ? self.flashButton : self.cancelButton;
    [button addSubview:web];
    web.frame = button.bounds;
    if (isFlash) {
        self.flashSvgView = web;
    } else {
        self.cancelSvgView = web;
    }
    [button setTitle:@"" forState:UIControlStateNormal];
}

- (void)refreshSvgForButton:(BOOL)isFlash active:(BOOL)active {
    WKWebView *web = isFlash ? self.flashSvgView : self.cancelSvgView;
    if (!web) {
        return;
    }
    NSString *svg = [self buttonIconValue:isFlash];
    if (svg.length == 0) {
        return;
    }
    UIColor *tint = [self buttonTextColorIsFlash:isFlash active:active];
    CGFloat iconSize = [self buttonContentSize];
    CGFloat r = 0, g = 0, b = 0, a = 1;
    [tint getRed:&r green:&g blue:&b alpha:&a];
    NSString *hex = [NSString stringWithFormat:@"#%02X%02X%02X", (int)(r * 255), (int)(g * 255), (int)(b * 255)];
    NSString *body;
    if ([svg hasPrefix:@"<svg"]) {
        body = [svg stringByReplacingOccurrencesOfString:@"<svg" withString:@"<svg fill=\"currentColor\""];
    } else {
        body = [NSString stringWithFormat:@"<img src=\"%@\" style=\"width:100%%;height:100%%;object-fit:contain;\"/>", svg];
    }
    NSString *html = [NSString stringWithFormat:@"<html><head><meta name='viewport' content='width=device-width, initial-scale=1'>"
                      "<style>html,body{margin:0;padding:0;background:transparent;width:100%%;height:100%%;display:flex;align-items:center;justify-content:center;color:%@;}svg,img{width:%.1fpx !important;height:%.1fpx !important;max-width:90%%;max-height:90%%;}</style></head><body>%@</body></html>", hex, iconSize, iconSize, body];
    [web loadHTMLString:html baseURL:nil];
}

- (void)refreshFlashButtonAppearance {
    [self setFlashVisualState:self.torchEnabled];
}

- (void)refreshCancelButtonAppearance {
    [self styleButton:self.cancelButton isFlash:NO active:NO];
    [self refreshSvgForButton:NO active:NO];
}

- (void)setupCamera {
    [self debugLog:@"setupCamera called"];
    AVAuthorizationStatus status = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];
    if (status == AVAuthorizationStatusNotDetermined) {
        [AVCaptureDevice requestAccessForMediaType:AVMediaTypeVideo completionHandler:^(BOOL granted) {
            dispatch_async(dispatch_get_main_queue(), ^{
                if (granted) {
                    [self debugLog:@"camera permission granted"];
                    [self configureSession];
                } else {
                    [self debugLog:@"camera permission denied by user"];
                    [self handleCancel:@"Camera permission denied."];
                }
            });
        }];
        return;
    }
    if (status != AVAuthorizationStatusAuthorized) {
        [self debugLog:@"camera permission is not authorized"];
        [self handleCancel:@"Camera permission denied."];
        return;
    }
    [self debugLog:@"camera permission already authorized"];
    [self configureSession];
}

- (void)configureSession {
    [self debugLog:@"configureSession start"];
    NSError *error = nil;
    BOOL preferFrontCamera = [self optBool:@"preferFrontCamera" defaultValue:NO];
    AVCaptureDevicePosition preferredPosition = preferFrontCamera ? AVCaptureDevicePositionFront : AVCaptureDevicePositionBack;

    AVCaptureDeviceDiscoverySession *discovery = [AVCaptureDeviceDiscoverySession
        discoverySessionWithDeviceTypes:@[AVCaptureDeviceTypeBuiltInWideAngleCamera]
                              mediaType:AVMediaTypeVideo
                               position:preferredPosition];
    AVCaptureDevice *device = discovery.devices.firstObject;
    if (!device) {
        device = [AVCaptureDevice defaultDeviceWithMediaType:AVMediaTypeVideo];
    }
    if (!device) {
        [self debugLog:@"camera device unavailable"];
        [self handleCancel:@"Camera device is not available."];
        return;
    }
    [self debugLog:[NSString stringWithFormat:@"selected camera position=%ld", (long)device.position]];
    self.videoInput = [AVCaptureDeviceInput deviceInputWithDevice:device error:&error];
    if (error || !self.videoInput) {
        [self debugLog:@"failed to create AVCaptureDeviceInput"];
        [self handleCancel:@"Failed to initialize camera input."];
        return;
    }

    // Improve scan reliability by forcing continuous autofocus/exposure when supported.
    NSError *deviceConfigError = nil;
    if ([device lockForConfiguration:&deviceConfigError]) {
        if ([device isFocusModeSupported:AVCaptureFocusModeContinuousAutoFocus]) {
            device.focusMode = AVCaptureFocusModeContinuousAutoFocus;
        }
        if ([device isExposureModeSupported:AVCaptureExposureModeContinuousAutoExposure]) {
            device.exposureMode = AVCaptureExposureModeContinuousAutoExposure;
        }
        [device unlockForConfiguration];
    } else {
        [self debugLog:[NSString stringWithFormat:@"device lock failed for focus config: %@", deviceConfigError.localizedDescription ?: @"unknown"]];
    }

    self.session = [[AVCaptureSession alloc] init];
    if ([self.session canSetSessionPreset:AVCaptureSessionPresetHigh]) {
        self.session.sessionPreset = AVCaptureSessionPresetHigh;
    }
    if ([self.session canAddInput:self.videoInput]) {
        [self.session addInput:self.videoInput];
    } else {
        [self debugLog:@"failed to add camera input to session"];
        [self handleCancel:@"Failed to attach camera input."];
        return;
    }

    self.metadataOutput = [[AVCaptureMetadataOutput alloc] init];
    if ([self.session canAddOutput:self.metadataOutput]) {
        [self.session addOutput:self.metadataOutput];
    } else {
        [self debugLog:@"failed to add metadata output to session"];
        [self handleCancel:@"Failed to initialize metadata output."];
        return;
    }

    [self.metadataOutput setMetadataObjectsDelegate:self queue:self.metadataQueue];

    self.videoDataOutput = [[AVCaptureVideoDataOutput alloc] init];
    self.videoDataOutput.alwaysDiscardsLateVideoFrames = YES;
    self.videoDataOutput.videoSettings = @{ (id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA) };
    [self.videoDataOutput setSampleBufferDelegate:self queue:self.visionQueue];
    if ([self.session canAddOutput:self.videoDataOutput]) {
        [self.session addOutput:self.videoDataOutput];
        [self debugLog:@"video data output added for Vision fallback"];
    } else {
        [self debugLog:@"failed to add video data output for Vision fallback"];
    }

    self.previewLayer = [AVCaptureVideoPreviewLayer layerWithSession:self.session];
    self.previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill;
    [self.view.layer insertSublayer:self.previewLayer atIndex:0];
    [self updateScanRect];
    [self.overlayView startAnimation];

    AVCaptureConnection *previewConnection = self.previewLayer.connection;
    AVCaptureConnection *metadataConnection = [self.metadataOutput connectionWithMediaType:AVMediaTypeVideo];
    AVCaptureConnection *videoConnection = [self.videoDataOutput connectionWithMediaType:AVMediaTypeVideo];
    if (@available(iOS 17.0, *)) {
        AVCaptureVideoOrientation orientation = AVCaptureVideoOrientationPortrait;
        UIWindowScene *scene = self.view.window.windowScene;
        if (scene) {
            switch (scene.interfaceOrientation) {
                case UIInterfaceOrientationLandscapeLeft:
                    orientation = AVCaptureVideoOrientationLandscapeLeft;
                    break;
                case UIInterfaceOrientationLandscapeRight:
                    orientation = AVCaptureVideoOrientationLandscapeRight;
                    break;
                case UIInterfaceOrientationPortraitUpsideDown:
                    orientation = AVCaptureVideoOrientationPortraitUpsideDown;
                    break;
                case UIInterfaceOrientationPortrait:
                default:
                    orientation = AVCaptureVideoOrientationPortrait;
                    break;
            }
        }
        if (previewConnection.isVideoOrientationSupported) {
            previewConnection.videoOrientation = orientation;
        }
        if (metadataConnection.isVideoOrientationSupported) {
            metadataConnection.videoOrientation = orientation;
        }
        if (videoConnection.isVideoOrientationSupported) {
            videoConnection.videoOrientation = orientation;
        }

        switch (orientation) {
            case AVCaptureVideoOrientationLandscapeLeft:
                self.visionOrientation = kCGImagePropertyOrientationUp;
                break;
            case AVCaptureVideoOrientationLandscapeRight:
                self.visionOrientation = kCGImagePropertyOrientationDown;
                break;
            case AVCaptureVideoOrientationPortraitUpsideDown:
                self.visionOrientation = kCGImagePropertyOrientationLeft;
                break;
            case AVCaptureVideoOrientationPortrait:
            default:
                self.visionOrientation = kCGImagePropertyOrientationRight;
                break;
        }
    } else {
        if (previewConnection.isVideoOrientationSupported) {
            previewConnection.videoOrientation = AVCaptureVideoOrientationPortrait;
        }
        if (metadataConnection.isVideoOrientationSupported) {
            metadataConnection.videoOrientation = AVCaptureVideoOrientationPortrait;
        }
        if (videoConnection.isVideoOrientationSupported) {
            videoConnection.videoOrientation = AVCaptureVideoOrientationPortrait;
        }
        self.visionOrientation = kCGImagePropertyOrientationRight;
    }

    dispatch_async(self.captureQueue, ^{
        [self.session startRunning];
        dispatch_async(dispatch_get_main_queue(), ^{
            NSArray<AVMetadataObjectType> *availableTypes = self.metadataOutput.availableMetadataObjectTypes;
            NSMutableArray<AVMetadataObjectType> *enabledTypes = [NSMutableArray array];
            if ([availableTypes containsObject:AVMetadataObjectTypeQRCode]) {
                [enabledTypes addObject:AVMetadataObjectTypeQRCode];
            }
#ifdef AVMetadataObjectTypeMicroQRCode
            if ([availableTypes containsObject:AVMetadataObjectTypeMicroQRCode]) {
                [enabledTypes addObject:AVMetadataObjectTypeMicroQRCode];
            }
#endif
            if (enabledTypes.count == 0) {
                [self debugLog:[NSString stringWithFormat:@"QR metadata types unavailable. available=%@", [availableTypes componentsJoinedByString:@","]]];
                [self handleCancel:@"QR metadata type is not available on this device."];
                return;
            }
            self.metadataOutput.metadataObjectTypes = enabledTypes;
            [self debugLog:[NSString stringWithFormat:@"metadata output configured: %@", [enabledTypes componentsJoinedByString:@","]]];

            [self debugLog:[NSString stringWithFormat:@"capture session started, running=%@", self.session.isRunning ? @"YES" : @"NO"]];
            [self scheduleNoDetectionDiagnostics];
        });
    });
}

- (void)scheduleNoDetectionDiagnostics {
    if (![self isDebugEnabled] || self.resultLocked) {
        return;
    }
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(3.0 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        if (!self.resultLocked) {
            [self debugLog:[NSString stringWithFormat:@"no QR detected yet, sessionRunning=%@, metadataTypes=%@", self.session.isRunning ? @"YES" : @"NO", [self.metadataOutput.metadataObjectTypes componentsJoinedByString:@","]]];
        }
    });
}

- (BOOL)shouldRestrictToScanZone {
    return [self optBool:@"restrictScanToZone" defaultValue:NO];
}

- (BOOL)isViewPointInsideScanZone:(CGPoint)point {
    CGRect zone = self.overlayView.scanRect;
    if (CGRectIsEmpty(zone)) {
        return YES;
    }
    return CGRectContainsPoint(zone, point);
}

- (void)updateScanRect {
    CGFloat width = [self optFloat:@"scanZoneWidth" defaultValue:260];
    CGFloat height = [self optFloat:@"scanZoneHeight" defaultValue:260];
    CGFloat offsetY = [self optFloat:@"scanZoneOffsetY" defaultValue:0];

    CGRect bounds = [self scannerContentFrame];
    CGRect scanRect = CGRectMake((bounds.size.width - width) / 2.0,
                                 (bounds.size.height - height) / 2.0 + offsetY,
                                 width,
                                 height);
    self.overlayView.scanRect = scanRect;
    [self.overlayView setNeedsDisplay];

    if (self.previewLayer && self.metadataOutput) {
        BOOL restrictToZone = [self shouldRestrictToScanZone];
        if (restrictToZone) {
            CGRect converted = [self.previewLayer metadataOutputRectOfInterestForRect:scanRect];
            self.metadataOutput.rectOfInterest = converted;
        } else {
            self.metadataOutput.rectOfInterest = CGRectMake(0, 0, 1, 1);
        }
    }
}

- (void)metadataOutput:(AVCaptureMetadataOutput *)output
didOutputMetadataObjects:(NSArray<__kindof AVMetadataObject *> *)metadataObjects
       fromConnection:(AVCaptureConnection *)connection {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (self.resultLocked) {
            return;
        }
        for (AVMetadataObject *object in metadataObjects) {
            if (![object isKindOfClass:[AVMetadataMachineReadableCodeObject class]]) {
                continue;
            }
            AVMetadataMachineReadableCodeObject *code = (AVMetadataMachineReadableCodeObject *)object;
            if ([self shouldRestrictToScanZone]) {
                AVMetadataMachineReadableCodeObject *transformed = (AVMetadataMachineReadableCodeObject *)[self.previewLayer transformedMetadataObjectForMetadataObject:code];
                if (transformed) {
                    CGPoint center = CGPointMake(CGRectGetMidX(transformed.bounds), CGRectGetMidY(transformed.bounds));
                    if (![self isViewPointInsideScanZone:center]) {
                        [self debugLog:@"metadata hit ignored (outside scan zone)"];
                        continue;
                    }
                }
            }
            NSString *value = code.stringValue;
            if (value.length == 0) {
                continue;
            }
            [self debugLog:[NSString stringWithFormat:@"qr detected via metadata, length=%lu type=%@", (unsigned long)value.length, code.type]];
            [self handleDetectedValue:value format:@"QR_CODE"];
            break;
        }
    });
}

- (void)captureOutput:(AVCaptureOutput *)output
didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer
       fromConnection:(AVCaptureConnection *)connection {
    if (self.resultLocked) {
        return;
    }
    CFTimeInterval now = CACurrentMediaTime();
    if ((now - self.lastVisionScanTime) < 0.25) {
        return;
    }
    self.lastVisionScanTime = now;

    if (@available(iOS 11.0, *)) {
        VNDetectBarcodesRequest *request = [[VNDetectBarcodesRequest alloc] init];
        request.symbologies = @[VNBarcodeSymbologyQR];
        VNImageRequestHandler *handler = [[VNImageRequestHandler alloc] initWithCMSampleBuffer:sampleBuffer orientation:self.visionOrientation options:@{}];
        NSError *error = nil;
        BOOL ok = [handler performRequests:@[request] error:&error];
        if (!ok || error) {
            if (error && [self isDebugEnabled]) {
                [self debugLog:[NSString stringWithFormat:@"Vision request failed: %@", error.localizedDescription ?: @"unknown"]];
            }
            return;
        }

        NSArray *results = request.results;
        for (VNBarcodeObservation *observation in results) {
            NSString *payload = observation.payloadStringValue;
            if (payload.length == 0) {
                continue;
            }
            if ([self shouldRestrictToScanZone]) {
                CGRect bbox = observation.boundingBox; // normalized, origin at bottom-left
                CGPoint normalizedCenter = CGPointMake(CGRectGetMidX(bbox), CGRectGetMidY(bbox));
                CGSize viewSize = self.view.bounds.size;
                CGPoint viewPoint = CGPointMake(normalizedCenter.x * viewSize.width,
                                                (1.0 - normalizedCenter.y) * viewSize.height);
                if (![self isViewPointInsideScanZone:viewPoint]) {
                    if ([self isDebugEnabled]) {
                        [self debugLog:[NSString stringWithFormat:@"Vision hit ignored (outside scan zone), point=(%.1f, %.1f)", viewPoint.x, viewPoint.y]];
                    }
                    continue;
                }
            }
            dispatch_async(dispatch_get_main_queue(), ^{
                if (self.resultLocked) {
                    return;
                }
                [self debugLog:[NSString stringWithFormat:@"qr detected via Vision, length=%lu orientation=%ld", (unsigned long)payload.length, (long)self.visionOrientation]];
                [self handleDetectedValue:payload format:@"QR_CODE"];
            });
            break;
        }
    }
}

- (void)handleDetectedValue:(NSString *)value format:(NSString *)format {
    if (self.resultLocked) {
        return;
    }
    self.resultLocked = YES;
    dispatch_async(self.captureQueue, ^{
        [self.session stopRunning];
    });

    BOOL showLoader = [self optBool:@"showLoader" defaultValue:YES];
    if (showLoader) {
        [self.loader startAnimating];
    }
    NSTimeInterval delayMs = [self optFloat:@"loadingDelayMs" defaultValue:700];
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(MAX(0, delayMs) * NSEC_PER_MSEC)), dispatch_get_main_queue(), ^{
        if ([self optBool:@"hapticFeedback" defaultValue:YES]) {
            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate);
        }
        NSDictionary *result = @{
            @"text": value ?: @"",
            @"format": format ?: @"QR_CODE",
            @"cancelled": @NO
        };
        [self.loader stopAnimating];
        if (self.onSuccess) {
            [self debugLog:@"onSuccess callback invoked"];
            self.onSuccess(result);
        }
    });
}

- (void)onFlashTap {
    BOOL isActive = [self toggleTorch];
    [self setFlashVisualState:isActive];
}

- (void)onCancelTap {
    [self debugLog:@"cancel button tapped"];
    [self refreshCancelButtonAppearance];
    [self handleCancel:@"Scan cancelled by user."];
}

- (void)handleCancel:(NSString *)message {
    [self debugLog:[NSString stringWithFormat:@"handleCancel: %@", message ?: @""]];
    dispatch_async(self.captureQueue, ^{
        [self.session stopRunning];
    });
    if (self.onCancel) {
        self.onCancel(message ?: @"Scan cancelled.");
    }
}

- (void)cancelFromPlugin {
    [self debugLog:@"cancelFromPlugin invoked"];
    [self handleCancel:@"Scan cancelled."];
}

- (BOOL)isTorchAvailable {
    AVCaptureDevice *device = self.videoInput.device;
    return device && [device hasTorch];
}

- (BOOL)toggleTorch {
    BOOL currentlyEnabled = [self torchEnabledState];
    [self debugLog:[NSString stringWithFormat:@"toggleTorch current=%@", currentlyEnabled ? @"ON" : @"OFF"]];
    [self setTorchEnabled:!currentlyEnabled];
    [self debugLog:[NSString stringWithFormat:@"toggleTorch result=%@", self.torchEnabled ? @"ON" : @"OFF"]];
    return self.torchEnabled;
}

- (void)setTorchEnabled:(BOOL)enabled {
    if (self.torchBusy) {
        return;
    }
    self.torchBusy = YES;
    BOOL previous = self.torchEnabled;

    AVCaptureDevice *device = self.videoInput.device;
    if (!device || ![device hasTorch] || ![device isTorchModeSupported:AVCaptureTorchModeOn]) {
        [self debugLog:@"torch not available/supported"];
        self.torchEnabled = NO;
        self.torchBusy = NO;
        [self setFlashVisualState:NO];
        return;
    }

    NSError *error = nil;
    BOOL locked = [device lockForConfiguration:&error];
    if (!locked) {
        [self debugLog:[NSString stringWithFormat:@"lockForConfiguration failed: %@", error.localizedDescription ?: @"unknown"]];
        self.torchEnabled = previous;
        self.torchBusy = NO;
        [self setFlashVisualState:self.torchEnabled];
        return;
    }

    @try {
        if (enabled) {
            if ([device respondsToSelector:@selector(setTorchModeOnWithLevel:error:)]) {
                NSError *levelError = nil;
                if (![device setTorchModeOnWithLevel:1.0 error:&levelError]) {
                    [self debugLog:[NSString stringWithFormat:@"setTorchModeOnWithLevel failed: %@", levelError.localizedDescription ?: @"unknown"]];
                    device.torchMode = AVCaptureTorchModeOn;
                }
            } else {
                device.torchMode = AVCaptureTorchModeOn;
            }
        } else {
            device.torchMode = AVCaptureTorchModeOff;
        }
        self.torchEnabled = (device.torchMode == AVCaptureTorchModeOn);
        [self debugLog:[NSString stringWithFormat:@"torch state now %@", self.torchEnabled ? @"ON" : @"OFF"]];
        if (enabled) {
            self.torchEnabled = YES;
        } else if (!enabled) {
            self.torchEnabled = NO;
        }
    } @finally {
        [device unlockForConfiguration];
        self.torchBusy = NO;
        dispatch_async(dispatch_get_main_queue(), ^{
            [self setFlashVisualState:self.torchEnabled];
        });
    }
}

- (void)applyFlashButtonVisualState:(BOOL)isFlashActive {
    [self styleButton:self.flashButton isFlash:YES active:isFlashActive];
}

- (void)setFlashVisualState:(BOOL)isFlashActive {
    [self applyFlashButtonVisualState:isFlashActive];
    [self refreshSvgForButton:YES active:isFlashActive];
}

- (BOOL)torchEnabledState {
    AVCaptureDevice *device = self.videoInput.device;
    if (!device || ![device hasTorch]) {
        return NO;
    }
    return device.torchMode == AVCaptureTorchModeOn;
}

- (void)viewWillDisappear:(BOOL)animated {
    [super viewWillDisappear:animated];
    [self debugLog:@"viewWillDisappear, stopping session"];
    dispatch_async(self.captureQueue, ^{
        [self.session stopRunning];
    });
}

@end
