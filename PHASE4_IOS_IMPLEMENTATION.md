# Phase 4: iOS Implementation Guide

## Overview
This guide covers the complete iOS implementation for the Kotlin Multiplatform Vocable AAC app, including MediaPipe integration, camera setup, and UI development.

---

## ✅ Completed: iOS Platform Layer (Kotlin/Native)

### 1. Logger.kt actual (NSLog wrapper)
**File:** `shared/src/iosMain/kotlin/com/vocable/platform/Logger.kt`

✅ **Implemented:**
- NSLogLogger wraps NSLog for logging
- Compatible logging interface with Android
- Factory: `createLogger(tag: String)`

```kotlin
val logger = createLogger("GazeTracker")
logger.debug("Processing frame...")
logger.error("Initialization failed", exception)
```

**Production Note:** For better performance, consider replacing NSLog with os_log:
```swift
import os.log

let log = OSLog(subsystem: "com.vocable.app", category: tag)
os_log("Message", log: log, type: .debug)
```

---

### 2. Storage.kt actual (UserDefaults wrapper)
**File:** `shared/src/iosMain/kotlin/com/vocable/platform/Storage.kt`

✅ **Implemented:**
- UserDefaultsStorage wraps NSUserDefaults
- CalibrationData serialization (CSV format for transforms)
- Support for String, Float, Boolean, Int primitives
- Automatic synchronization after writes

```kotlin
val storage = createStorage()
storage.saveCalibrationData(calibrationData, "polynomial")
val loaded = storage.loadCalibrationData("polynomial")
```

**Features:**
- Same serialization format as Android (CSV transforms)
- Cross-platform compatible calibration data
- Persistent across app launches

---

### 3. FaceLandmarkDetector.kt actual (MediaPipe iOS stub)
**File:** `shared/src/iosMain/kotlin/com/vocable/platform/FaceLandmarkDetector.kt`

⚠️ **Stub Implementation:**
- Structure and interface complete
- Requires MediaPipe iOS SDK integration via Swift wrapper
- Detailed implementation notes included in code

**Next Steps:** Full MediaPipe integration (see below)

---

## 📋 iOS Project Setup

### Step 1: Create iOS App in Xcode

1. **Open Xcode**
2. **Create New Project:**
   - Choose "App" template
   - Product Name: "Vocable AAC"
   - Organization Identifier: com.vocable (or your identifier)
   - Interface: SwiftUI
   - Language: Swift
   - Save in: `Switch2Connect_AAC/iosApp/`

3. **Project Structure:**
```
Switch2Connect_AAC/
├── shared/                    (KMP shared module)
├── app/                       (Android app)
└── iosApp/
    ├── iosApp/
    │   ├── Assets.xcassets
    │   ├── Preview Content
    │   ├── ContentView.swift
    │   ├── VocableApp.swift  (App entry point)
    │   └── Info.plist
    ├── iosApp.xcodeproj
    └── Podfile (to be created)
```

---

### Step 2: Integrate KMP Shared Framework

1. **Build iOS Framework from Shared Module:**
```bash
cd Switch2Connect_AAC
./gradlew :shared:assembleVocableSharedReleaseXCFramework
```

This generates:
```
shared/build/XCFrameworks/release/VocableShared.xcframework
```

2. **Add Framework to Xcode:**
   - Open `iosApp.xcodeproj` in Xcode
   - Drag `VocableShared.xcframework` into project navigator
   - Target Membership: iosApp
   - Embed & Sign: "Embed & Sign"

3. **Import in Swift:**
```swift
import VocableShared

// Access shared module
let logger = LoggerKt.createLogger(tag: "MyTag")
logger.debug(message: "Hello from Swift!")

let storage = StorageKt.createStorage()
```

---

### Step 3: MediaPipe iOS Integration

#### 3.1 Install CocoaPods

```bash
# If not already installed
sudo gem install cocoapods
```

#### 3.2 Create Podfile

Create `iosApp/Podfile`:
```ruby
platform :ios, '14.0'

target 'iosApp' do
  use_frameworks!

  # MediaPipe Tasks Vision
  pod 'MediaPipeTasksVision', '~> 0.10.14'

  # Optional: Additional dependencies
  # pod 'GoogleMLKit/FaceDetection'
end

post_install do |installer|
  installer.pods_project.targets.each do |target|
    target.build_configurations.each do |config|
      config.build_settings['IPHONEOS_DEPLOYMENT_TARGET'] = '14.0'
    end
  end
end
```

#### 3.3 Install Pods

```bash
cd iosApp
pod install
```

**Important:** From now on, open `iosApp.xcworkspace` (NOT .xcodeproj)

#### 3.4 Add Model File to Bundle

1. Copy `face_landmarker.task` to `iosApp/iosApp/Resources/`
2. In Xcode, add to project:
   - Right-click `iosApp` folder
   - Add Files to "iosApp"
   - Select `face_landmarker.task`
   - Target Membership: iosApp

---

### Step 4: Swift Wrapper for MediaPipe

Create `iosApp/iosApp/MediaPipe/FaceLandmarkerWrapper.swift`:

```swift
import Foundation
import MediaPipeTasksVision
import VocableShared

class FaceLandmarkerWrapper {
    private var faceLandmarker: FaceLandmarker?
    private var isInitialized = false
    private var useGpu = false

    func initialize(useGpu: Bool) -> Bool {
        do {
            // Load model from bundle
            guard let modelPath = Bundle.main.path(forResource: "face_landmarker", ofType: "task") else {
                print("Failed to find face_landmarker.task in bundle")
                return false
            }

            // Configure base options
            let baseOptions = BaseOptions()
            baseOptions.modelAssetPath = modelPath
            baseOptions.delegate = useGpu ? .GPU : .CPU

            // Configure face landmarker options
            let options = FaceLandmarkerOptions()
            options.baseOptions = baseOptions
            options.runningMode = .image
            options.numFaces = 1
            options.minFaceDetectionConfidence = 0.5
            options.minFacePresenceConfidence = 0.5
            options.minTrackingConfidence = 0.5

            // Create face landmarker
            faceLandmarker = try FaceLandmarker(options: options)
            isInitialized = true
            self.useGpu = useGpu

            print("MediaPipe FaceLandmarker initialized (GPU: \(useGpu))")
            return true

        } catch {
            print("Failed to initialize FaceLandmarker: \(error)")
            return false
        }
    }

    func detectLandmarks(image: MPImage) -> FaceLandmarkResultKt? {
        guard isInitialized, let landmarker = faceLandmarker else {
            print("FaceLandmarker not initialized")
            return nil
        }

        do {
            let result = try landmarker.detect(image: image)
            guard let landmarks = result.faceLandmarks.first else {
                return nil
            }

            // Convert MediaPipe landmarks to KMP LandmarkPoint
            let landmarkPoints = landmarks.map { landmark in
                LandmarkPoint(
                    x: landmark.x,
                    y: landmark.y,
                    z: landmark.z
                )
            }

            // Get frame dimensions
            let frameWidth = Int32(image.width)
            let frameHeight = Int32(image.height)
            let timestamp = Int64(Date().timeIntervalSince1970 * 1000)

            return FaceLandmarkResultKt(
                landmarks: landmarkPoints,
                frameWidth: frameWidth,
                frameHeight: frameHeight,
                timestamp: timestamp
            )

        } catch {
            print("Error detecting landmarks: \(error)")
            return nil
        }
    }

    func close() {
        faceLandmarker = nil
        isInitialized = false
    }
}
```

---

### Step 5: Camera Capture with AVFoundation

Create `iosApp/iosApp/Camera/CameraManager.swift`:

```swift
import AVFoundation
import UIKit
import MediaPipeTasksVision

class CameraManager: NSObject, ObservableObject {
    private let captureSession = AVCaptureSession()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let sessionQueue = DispatchQueue(label: "camera.session.queue")
    private let faceLandmarker = FaceLandmarkerWrapper()

    @Published var isRunning = false
    @Published var currentGaze: (Int, Int)?

    // Callback for gaze updates
    var onGazeUpdate: ((Int, Int) -> Void)?

    override init() {
        super.init()
    }

    func setupCamera() {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }

            // Configure session
            self.captureSession.beginConfiguration()
            self.captureSession.sessionPreset = .hd1280x720

            // Add video input (front camera)
            guard let frontCamera = AVCaptureDevice.default(
                .builtInWideAngleCamera,
                for: .video,
                position: .front
            ) else {
                print("Failed to get front camera")
                return
            }

            do {
                let input = try AVCaptureDeviceInput(device: frontCamera)
                if self.captureSession.canAddInput(input) {
                    self.captureSession.addInput(input)
                }
            } catch {
                print("Failed to create camera input: \(error)")
                return
            }

            // Configure video output
            self.videoOutput.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
            ]
            self.videoOutput.setSampleBufferDelegate(self, queue: self.sessionQueue)

            if self.captureSession.canAddOutput(self.videoOutput) {
                self.captureSession.addOutput(self.videoOutput)
            }

            self.captureSession.commitConfiguration()

            // Initialize MediaPipe
            _ = self.faceLandmarker.initialize(useGpu: true)
        }
    }

    func startCamera() {
        sessionQueue.async { [weak self] in
            self?.captureSession.startRunning()
            DispatchQueue.main.async {
                self?.isRunning = true
            }
        }
    }

    func stopCamera() {
        sessionQueue.async { [weak self] in
            self?.captureSession.stopRunning()
            DispatchQueue.main.async {
                self?.isRunning = false
            }
        }
    }
}

// MARK: - AVCaptureVideoDataOutputSampleBufferDelegate
extension CameraManager: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        // Convert CMSampleBuffer to CVPixelBuffer
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            return
        }

        // Convert CVPixelBuffer to MPImage
        guard let mpImage = try? MPImage(pixelBuffer: pixelBuffer) else {
            return
        }

        // Detect landmarks
        guard let landmarkResult = faceLandmarker.detectLandmarks(image: mpImage) else {
            return
        }

        // Process gaze using shared module
        // TODO: Integrate with GazeTracker from shared module
        // let gazeTracker = ... (create instance)
        // let gazeResult = gazeTracker.processFrame(landmarkResult)
        // let (screenX, screenY) = gazeTracker.gazeToScreen(gazeResult)

        // For now, just log that we got landmarks
        print("Detected \(landmarkResult.landmarks.count) landmarks")

        // Update UI on main thread
        // DispatchQueue.main.async {
        //     self.currentGaze = (screenX, screenY)
        //     self.onGazeUpdate?(screenX, screenY)
        // }
    }
}
```

---

### Step 6: SwiftUI Integration

Update `iosApp/iosApp/ContentView.swift`:

```swift
import SwiftUI
import VocableShared

struct ContentView: View {
    @StateObject private var cameraManager = CameraManager()
    @State private var gazePointer: CGPoint = .zero

    var body: some View {
        ZStack {
            // Camera preview (optional)
            Color.black.edgesIgnoringSafeArea(.all)

            // Gaze pointer
            Circle()
                .fill(Color.red.opacity(0.7))
                .frame(width: 30, height: 30)
                .position(gazePointer)

            // AAC UI (phrases, categories, etc.)
            VStack {
                Spacer()

                // Example phrase buttons
                HStack {
                    phraseButton(text: "Hello")
                    phraseButton(text: "Thank you")
                    phraseButton(text: "Yes")
                    phraseButton(text: "No")
                }
                .padding()
            }
        }
        .onAppear {
            cameraManager.setupCamera()
            cameraManager.startCamera()

            // Handle gaze updates
            cameraManager.onGazeUpdate = { x, y in
                gazePointer = CGPoint(x: x, y: y)
            }
        }
        .onDisappear {
            cameraManager.stopCamera()
        }
    }

    func phraseButton(text: String) -> some View {
        Text(text)
            .font(.title2)
            .padding()
            .background(Color.blue)
            .foregroundColor(.white)
            .cornerRadius(10)
    }
}
```

---

## 🚀 Using Shared KMP Module from Swift

### Initialize GazeTracker

```swift
import VocableShared

class GazeTrackerManager: ObservableObject {
    private var gazeTracker: GazeTracker?
    private let faceLandmarkDetector = PlatformFaceLandmarkDetector()
    private let logger = LoggerKt.createLogger(tag: "GazeTracker")
    private let storage = StorageKt.createStorage()

    func initialize(screenWidth: Int32, screenHeight: Int32) {
        gazeTracker = GazeTracker(
            faceLandmarkDetector: faceLandmarkDetector,
            screenWidth: screenWidth,
            screenHeight: screenHeight,
            storage: storage,
            logger: logger
        )

        // Configure
        gazeTracker?.smoothingMode = .adaptiveKalman
        gazeTracker?.eyeSelection = .bothEyes

        // Load saved calibration
        _ = gazeTracker?.loadCalibration()
    }

    func processFrame(landmarkResult: FaceLandmarkResultKt) async -> (Int, Int)? {
        // The shared module's processFrame expects the detector to return landmarks
        // Since we already have landmarks from Swift wrapper, we need to adapt

        // Option 1: Create a custom wrapper that returns pre-detected landmarks
        // Option 2: Pass landmarks directly to GazeTracker internals

        // For now, return placeholder
        return nil
    }
}
```

### Calibration Flow

```swift
func startCalibration() {
    guard let gazeTracker = gazeTracker else { return }
    let calibration = gazeTracker.getCalibration()

    // Generate 9-point grid
    let points = calibration.generateCalibrationPoints(marginPercent: 0.1)

    for (index, point) in points.enumerated() {
        let (x, y) = point

        // Show calibration target at (x, y)
        showCalibrationTarget(at: CGPoint(x: x, y: y))

        // Collect samples (30-60 recommended)
        for _ in 0..<30 {
            // Process current frame and get gaze
            // let gazeResult = await processCurrentFrame()
            // calibration.addCalibrationSample(
            //     pointIndex: Int32(index),
            //     gazeX: gazeResult.gazeX,
            //     gazeY: gazeResult.gazeY
            // )

            try? await Task.sleep(nanoseconds: 50_000_000) // 50ms
        }
    }

    // Compute calibration
    if calibration.computeCalibration() {
        _ = gazeTracker?.saveCalibration()
        print("Calibration saved! Error: \(calibration.getCalibrationError())px")
    }
}
```

---

## 📝 Implementation Checklist

### iOS Project Setup
- [ ] Create Xcode project in iosApp/
- [ ] Add VocableShared.xcframework to project
- [ ] Configure Info.plist (camera permissions)
- [ ] Set deployment target to iOS 14.0+

### MediaPipe Integration
- [ ] Create Podfile with MediaPipeTasksVision
- [ ] Run `pod install`
- [ ] Add face_landmarker.task to bundle
- [ ] Create Swift wrapper (FaceLandmarkerWrapper.swift)
- [ ] Test MediaPipe landmark detection

### Camera Setup
- [ ] Implement CameraManager with AVFoundation
- [ ] Configure front camera capture
- [ ] Set up frame processing pipeline
- [ ] Convert CVPixelBuffer → MPImage

### Shared Module Integration
- [ ] Build iOS framework from shared module
- [ ] Import VocableShared in Swift
- [ ] Create GazeTrackerManager
- [ ] Integrate with camera frame processing
- [ ] Test gaze tracking end-to-end

### UI Development
- [ ] Create SwiftUI gaze pointer view
- [ ] Implement AAC phrase grid
- [ ] Add calibration UI
- [ ] Implement settings screen
- [ ] Add dwell-time selection
- [ ] Test on device (not simulator - camera required)

---

## 🔍 Debugging Tips

### MediaPipe Issues
```swift
// Enable MediaPipe logging
MPPTasksVisionOptions.setDefaultLogLevel(.debug)

// Check model loading
if let modelPath = Bundle.main.path(forResource: "face_landmarker", ofType: "task") {
    print("Model found at: \(modelPath)")
} else {
    print("⚠️ Model not found in bundle!")
}
```

### Camera Issues
```swift
// Check camera authorization
let status = AVCaptureDevice.authorizationStatus(for: .video)
switch status {
case .authorized:
    print("Camera authorized")
case .notDetermined:
    AVCaptureDevice.requestAccess(for: .video) { granted in
        print("Camera access: \(granted)")
    }
case .denied, .restricted:
    print("⚠️ Camera access denied")
@unknown default:
    break
}
```

### Shared Module Issues
```swift
// Test logger
let logger = LoggerKt.createLogger(tag: "Test")
logger.debug(message: "Logger working!")

// Test storage
let storage = StorageKt.createStorage()
storage.saveString(key: "test", value: "hello")
print("Stored value: \(storage.loadString(key: "test", defaultValue: ""))")
```

---

## 📱 Info.plist Configuration

Add camera permission to `iosApp/Info.plist`:

```xml
<key>NSCameraUsageDescription</key>
<string>This app uses the camera for eye gaze tracking to enable communication.</string>
```

---

## ⚙️ Build Configuration

### Xcode Build Settings

1. **Swift Language Version:** Swift 5
2. **iOS Deployment Target:** 14.0 or higher
3. **Enable Bitcode:** No (required for some frameworks)
4. **Framework Search Paths:** Add path to VocableShared.xcframework

### Gradle Configuration

In `shared/build.gradle.kts`, iOS targets are already configured:
```kotlin
iosX64()
iosArm64()
iosSimulatorArm64()
```

Build framework:
```bash
./gradlew :shared:linkDebugFrameworkIosArm64
```

---

## 🎯 Performance Optimization

### Frame Rate
- Target: 30-60 fps
- Use `videoOutput.setSampleBufferDelegate` with dedicated queue
- Process frames asynchronously
- Skip frames if processing falls behind

### Memory Management
```swift
// Reuse pixel buffer pool
var pixelBufferPool: CVPixelBufferPool?

// Clean up after each frame
autoreleasepool {
    // Frame processing code
}
```

### GPU Acceleration
```swift
// Enable GPU delegate for MediaPipe
baseOptions.delegate = .GPU

// Monitor GPU usage in Instruments (GPU Driver)
```

---

## 📊 Testing Strategy

### Unit Tests
- Test shared module functions from Swift
- Verify calibration save/load
- Test gaze calculation accuracy

### Device Testing
⚠️ **Simulator limitations:**
- No camera access
- MediaPipe may not work fully

**Test on real device:**
- iPhone 11 or newer (A13+ chip recommended)
- iOS 14.0 or higher
- Front TrueDepth camera preferred

### Performance Profiling
Use Xcode Instruments:
- Time Profiler (CPU usage)
- Allocations (memory)
- GPU Driver (GPU usage)
- Energy Log (battery impact)

---

## 🔗 Resources

### Documentation
- [MediaPipe Tasks iOS Guide](https://developers.google.com/mediapipe/solutions/vision/face_landmarker/ios)
- [AVFoundation Camera Guide](https://developer.apple.com/documentation/avfoundation/cameras_and_media_capture)
- [SwiftUI Documentation](https://developer.apple.com/documentation/swiftui)
- [Kotlin/Native iOS Integration](https://kotlinlang.org/docs/native-ios-integration.html)

### Sample Code
- [MediaPipe iOS Examples](https://github.com/google/mediapipe/tree/master/mediapipe/examples/ios)
- [AVFoundation Camera Sample](https://developer.apple.com/documentation/avfoundation/capture_setup/avcam_building_a_camera_app)

---

## ✅ Phase 4 Status

### Completed
- ✅ Logger.kt actual (NSLog wrapper)
- ✅ Storage.kt actual (UserDefaults wrapper)
- ✅ FaceLandmarkDetector.kt actual (stub with integration guide)
- ✅ Comprehensive iOS implementation documentation

### Next Steps
1. Create Xcode project
2. Integrate MediaPipe iOS SDK
3. Implement Swift wrappers
4. Build camera capture pipeline
5. Create SwiftUI UI
6. Test on device

**Estimated Time: 5-7 days for complete iOS implementation**

---

## 💡 Tips for Success

1. **Start Small:** Get camera preview working first
2. **Test Early:** Test on device as soon as possible
3. **Use Logging:** Add debug logs throughout
4. **Profile Often:** Monitor performance from the start
5. **Reference Android:** iOS implementation should mirror Android behavior

---

**Ready to build the iOS app!** 🚀

See Android implementation in `PHASE3_ANDROID_INTEGRATION.md` for comparison.
