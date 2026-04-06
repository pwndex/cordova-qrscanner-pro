#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

@interface QRScannerViewController : UIViewController

@property (nonatomic, copy) void (^onSuccess)(NSDictionary *result);
@property (nonatomic, copy) void (^onCancel)(NSString *message);
- (instancetype)initWithOptions:(NSDictionary *)options;
- (BOOL)isDebugEnabled;
- (NSString *)debugTag;
- (void)cancelFromPlugin;
- (BOOL)toggleTorch;
- (void)setTorchEnabled:(BOOL)enabled;
- (BOOL)torchEnabledState;
- (BOOL)isTorchAvailable;

@end

NS_ASSUME_NONNULL_END
