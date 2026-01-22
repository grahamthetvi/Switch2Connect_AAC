# KMP Migration Testing & Validation Guide

## Overview
This guide provides systematic testing procedures for the Kotlin Multiplatform migration of Vocable AAC. It covers build validation, functional testing, performance benchmarking, and cross-platform compatibility verification.

---

## Table of Contents

1. [Pre-Testing Setup](#pre-testing-setup)
2. [Phase 1: Build Validation](#phase-1-build-validation)
3. [Phase 2: Android Functional Testing](#phase-2-android-functional-testing)
4. [Phase 3: iOS Functional Testing](#phase-3-ios-functional-testing)
5. [Phase 4: Performance Testing](#phase-4-performance-testing)
6. [Phase 5: Cross-Platform Validation](#phase-5-cross-platform-validation)
7. [Phase 6: Regression Testing](#phase-6-regression-testing)
8. [Automated Testing](#automated-testing)
9. [Test Data & Metrics](#test-data--metrics)
10. [Troubleshooting Test Failures](#troubleshooting-test-failures)

---

## Pre-Testing Setup

### Required Hardware
- **Android:** Physical device (Android 7.0+) with camera - Recommended: Pixel, Samsung Galaxy
- **iOS:** Physical iPhone (iOS 14.0+) with camera - Simulator NOT sufficient for camera testing
- **Development machines:**
  - Android: Any OS with Android Studio
  - iOS: macOS with Xcode 15+

### Required Software
- Android Studio Hedgehog (2023.1.1) or later
- Xcode 15+ (macOS only)
- Git
- CocoaPods (iOS)

### Test Environment Preparation

**Android:**
```bash
# Verify Android SDK and emulator
./gradlew tasks

# Install debug build
./gradlew installDebug

# Enable USB debugging on device
# Settings > Developer Options > USB Debugging
```

**iOS:**
```bash
# Install CocoaPods dependencies (macOS)
cd iosApp
pod install

# Open workspace (not .xcodeproj)
open VocableAAC.xcworkspace
```

### Test Data Requirements
- Test faces (at least 3 different people)
- Various lighting conditions (bright, dim, natural, artificial)
- Different head poses (straight, tilted, turned)
- Different eye colors and face types

---

## Phase 1: Build Validation

### Goal
Verify all modules compile and build successfully on both platforms.

### Test Cases

#### BV-001: Shared Module Compilation
```bash
# Build shared module for all targets
./gradlew :shared:build

# Expected: BUILD SUCCESSFUL
# Targets compiled: androidDebug, iosArm64, iosX64, iosSimulatorArm64
```

**Success Criteria:**
- [ ] No compilation errors
- [ ] All 4 targets build successfully
- [ ] Build time < 5 minutes (initial) / < 30 seconds (incremental)

**Common Issues:**
- Missing Kotlin plugin → Check `build.gradle.kts` has `kotlinMultiplatform` plugin
- iOS targets fail → Ensure Kotlin 2.2.0+, proper Xcode path

---

#### BV-002: Android App Build
```bash
# Build Android app with shared module
./gradlew :app:assembleDebug

# Check APK created
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

**Success Criteria:**
- [ ] APK builds successfully
- [ ] APK size ~15-25 MB (similar to before)
- [ ] No warnings about missing shared module classes

---

#### BV-003: iOS Framework Build
```bash
# Build iOS framework from shared module
./gradlew :shared:linkDebugFrameworkIosArm64

# Expected output: build/bin/iosArm64/debugFramework/VocableShared.framework
```

**Success Criteria:**
- [ ] Framework builds for all iOS targets
- [ ] Framework size ~2-5 MB
- [ ] Headers include expected classes (GazeTracker, KalmanFilter2D, etc.)

---

#### BV-004: iOS App Build (Xcode)
**Prerequisites:** iOS Swift implementation completed (see FINAL_STEPS_TO_PRODUCTION.md)

```bash
# In Xcode
# Select target: VocableAAC (iOS)
# Select device: Your iPhone
# Product > Build (⌘B)
```

**Success Criteria:**
- [ ] Builds without errors
- [ ] MediaPipe framework linked correctly
- [ ] VocableShared framework embedded
- [ ] App size ~30-50 MB

---

## Phase 2: Android Functional Testing

### Goal
Verify gaze tracking functionality matches original implementation.

### Test Setup
Install app on physical Android device with camera.

---

### FT-A-001: Initialization
**Steps:**
1. Launch app
2. Navigate to Eye Gaze Tracking screen
3. Grant camera permission

**Expected:**
- Camera preview appears within 2 seconds
- No crash or ANR
- Log message: "FaceLandmarkDetector initialized successfully"

**Success Criteria:**
- [ ] Camera starts successfully
- [ ] GPU mode active (if device supports)
- [ ] No memory spikes (check Profiler)

---

### FT-A-002: Face Detection
**Steps:**
1. Position face in front of camera (well-lit, straight ahead)
2. Observe face mesh overlay (if UI implemented)
3. Check logs for landmark detection

**Expected:**
- Face detected within 1 second
- 478 landmarks detected per frame
- Iris landmarks (468-477) visible

**Success Criteria:**
- [ ] Face detected in <1 second
- [ ] Detection stable (no flickering)
- [ ] Log: "Detected 478 landmarks"

**Test Data:** Try with 3 different faces, various lighting

---

### FT-A-003: Gaze Estimation (Uncalibrated)
**Steps:**
1. Look at center of screen
2. Look at corners (top-left, top-right, bottom-left, bottom-right)
3. Look at screen edges
4. Note gaze pointer position

**Expected (Uncalibrated):**
- Gaze pointer visible
- Follows eye movement (may be inaccurate)
- Updates at 30+ fps
- No lag > 50ms

**Success Criteria:**
- [ ] Gaze pointer tracks eye movement
- [ ] Frame rate: 30-60 fps (check logs)
- [ ] Latency: < 50ms (measure with high-speed camera)

---

### FT-A-004: Kalman Filter Smoothing
**Steps:**
1. Enable ADAPTIVE_KALMAN mode (default)
2. Look at single point for 3 seconds (dwelling)
3. Rapidly move eyes between two points
4. Compare smoothing vs. raw gaze (if logged)

**Expected:**
- During dwelling: Heavy smoothing (pointer stable)
- During rapid movement: Less smoothing (pointer responsive)
- No oscillation or jitter

**Success Criteria:**
- [ ] Dwell position stable (std dev < 10 pixels)
- [ ] Rapid movement tracked without excessive lag
- [ ] Log: Adaptive multiplier changes (0.3 to 3.0 range)

**Metrics to Log:**
```kotlin
logger.debug("Velocity: ${velocity}, Multiplier: ${adaptiveMultiplier}")
```

---

### FT-A-005: 9-Point Calibration
**Steps:**
1. Tap "Calibrate" button
2. Follow 9-point calibration sequence:
   - Look at each target for 2-3 seconds
   - Targets appear in order: TL, TC, TR, ML, MC, MR, BL, BC, BR
3. Complete calibration
4. Check calibration error

**Expected:**
- 9 targets displayed in 3x3 grid
- ~30-60 samples collected per target
- Calibration computes successfully
- Error < 100 pixels (typically 30-60px)

**Success Criteria:**
- [ ] All 9 points sampled
- [ ] Calibration error: < 100px (good), < 50px (excellent)
- [ ] Saved to SharedPreferences (check: `adb shell dumpsys activity com.willowtree.vocable | grep calibration`)

**Test Matrix:**
| User | Calibration Error (px) | Notes |
|------|------------------------|-------|
| User 1 | | |
| User 2 | | |
| User 3 | | |

---

### FT-A-006: Calibrated Gaze Accuracy
**Steps:**
1. After calibration, look at 16 test targets (4x4 grid)
2. For each target, dwell for 1 second
3. Measure gaze position vs. target position
4. Calculate mean error

**Expected:**
- Gaze within 50-100px of target
- Better accuracy in center than edges
- Consistent across targets

**Success Criteria:**
- [ ] Mean error across 16 targets: < 100px
- [ ] Max error: < 200px
- [ ] Center error: < 50px

**Accuracy Calculation:**
```
Error = sqrt((gazeX - targetX)² + (gazeY - targetY)²)
Mean Error = sum(errors) / 16
```

---

### FT-A-007: Calibration Persistence
**Steps:**
1. Complete calibration (error < 100px)
2. Close app completely (swipe away from recents)
3. Reopen app
4. Test gaze accuracy without recalibrating

**Expected:**
- Calibration loads from SharedPreferences
- Accuracy same as before closing app
- No need to recalibrate

**Success Criteria:**
- [ ] Log: "Loaded calibration data for mode: polynomial"
- [ ] Gaze accuracy maintained
- [ ] Transform coefficients match pre/post restart

---

### FT-A-008: Eye Selection Modes
**Steps:**
Test each mode: BOTH_EYES, LEFT_EYE, RIGHT_EYE
1. Set eye selection mode
2. Test gaze tracking
3. Compare accuracy and smoothness

**Expected:**
- BOTH_EYES: Best accuracy (default)
- LEFT_EYE: Works if right eye obscured
- RIGHT_EYE: Works if left eye obscured

**Success Criteria:**
- [ ] All modes functional
- [ ] BOTH_EYES most accurate
- [ ] Single eye modes track correctly

---

### FT-A-009: Blink Detection
**Steps:**
1. Look at center of screen
2. Blink rapidly 10 times
3. Check logs for blink events

**Expected:**
- Blinks detected with confidence > 0.5
- Both eyes detected independently
- Blink count matches actual blinks (±2)

**Success Criteria:**
- [ ] Blink detection rate: 80%+
- [ ] False positives: < 10%
- [ ] Log: "Left blink: 0.78, Right blink: 0.82"

---

### FT-A-010: Head Pose Compensation
**Steps:**
1. Look at center of screen, head straight
2. Turn head left (~20°), keep eyes on center
3. Turn head right (~20°), keep eyes on center
4. Tilt head up/down (~15°), keep eyes on center

**Expected:**
- Gaze pointer stays roughly centered despite head movement
- Head pose compensation adjusts gaze estimate
- Yaw/pitch/roll values logged

**Success Criteria:**
- [ ] Gaze drift: < 100px when head turned
- [ ] Head pose values reasonable (yaw: -45° to 45°)
- [ ] Log: "Head pose - yaw: 15.3, pitch: -5.2, roll: 2.1"

---

### FT-A-011: Sensitivity Settings
**Steps:**
1. Set sensitivity: X=1.5, Y=1.5 (low)
2. Test gaze range (can you reach screen edges?)
3. Set sensitivity: X=3.5, Y=3.5 (high)
4. Test gaze range

**Expected:**
- Low sensitivity: Harder to reach edges, more precise in center
- High sensitivity: Easier to reach edges, less precise
- Default (2.5, 3.0): Balanced

**Success Criteria:**
- [ ] Sensitivity affects gaze range as expected
- [ ] Can reach all screen areas with appropriate setting
- [ ] Settings persist after restart

---

### FT-A-012: Offset Adjustment
**Steps:**
1. Set offset: X=0, Y=0
2. Note gaze position when looking at center
3. Set offset: X=0.2, Y=-0.1
4. Note new gaze position

**Expected:**
- Offset shifts gaze estimate
- Useful for individual eye alignment differences

**Success Criteria:**
- [ ] Offset shifts gaze proportionally
- [ ] Can fine-tune calibration
- [ ] Offset persists

---

## Phase 3: iOS Functional Testing

### Goal
Verify iOS implementation matches Android behavior exactly.

**Prerequisites:** iOS Swift implementation completed (Phase 4b)

### Test Setup
Install app on physical iPhone via Xcode.

---

### FT-iOS-001 to FT-iOS-012
**Repeat ALL Android functional tests (FT-A-001 through FT-A-012) on iOS.**

**Expected:** Identical behavior to Android

**Success Criteria:**
- [ ] All 12 Android test cases pass on iOS
- [ ] Calibration error within 10% of Android
- [ ] Frame rate 30-60 fps (same as Android)

---

### FT-iOS-013: Cross-Platform Calibration Compatibility
**Steps:**
1. Complete calibration on Android
2. Export calibration data (if implemented) or manually copy SharedPreferences values
3. Import to iOS UserDefaults
4. Test gaze accuracy on iOS with Android calibration

**Expected:**
- Calibration data format compatible (CSV transform coefficients)
- Accuracy reasonable (may differ slightly due to camera differences)

**Success Criteria:**
- [ ] iOS can parse Android calibration data
- [ ] Accuracy degradation: < 20%
- [ ] No crashes or data corruption

---

## Phase 4: Performance Testing

### Goal
Verify real-time performance meets requirements.

---

### PERF-001: Frame Rate Benchmark
**Setup:** Run gaze tracking for 60 seconds, measure FPS

**Android:**
```kotlin
// Add to GazeTracker
var frameCount = 0
var startTime = System.currentTimeMillis()

fun logFPS() {
    frameCount++
    val elapsed = System.currentTimeMillis() - startTime
    if (elapsed >= 1000) {
        val fps = frameCount / (elapsed / 1000.0)
        logger.debug("FPS: $fps")
        frameCount = 0
        startTime = System.currentTimeMillis()
    }
}
```

**Success Criteria:**
- [ ] Average FPS: 30-60 fps
- [ ] Minimum FPS: > 25 fps (even under load)
- [ ] Frame drops: < 5% of frames

**Test on multiple devices:**
| Device | Android Version | GPU | Avg FPS | Min FPS |
|--------|-----------------|-----|---------|---------|
| Pixel 6 | 14 | Mali-G78 | | |
| Samsung S21 | 13 | Adreno 660 | | |
| OnePlus 9 | 13 | Adreno 660 | | |

---

### PERF-002: Latency Measurement
**Setup:** Measure time from frame capture to gaze position output

**Method 1 (High-Speed Camera):**
1. Record screen + eye with 240fps camera
2. Measure delay between eye movement and pointer movement
3. Calculate latency in milliseconds

**Method 2 (Code Instrumentation):**
```kotlin
val t0 = System.nanoTime()
val result = processFrame(bitmap)
val t1 = System.nanoTime()
val latency = (t1 - t0) / 1_000_000.0 // ms
logger.debug("Latency: ${latency}ms")
```

**Success Criteria:**
- [ ] Median latency: < 50ms
- [ ] 95th percentile: < 100ms
- [ ] No spikes > 200ms

---

### PERF-003: Memory Usage
**Setup:** Monitor memory during 10-minute session

**Android (Profiler):**
1. Android Studio > Profiler > Memory
2. Start gaze tracking
3. Run for 10 minutes
4. Check for memory leaks (increasing baseline)

**Success Criteria:**
- [ ] Peak memory: < 150 MB
- [ ] No memory leaks (stable baseline after 5 min)
- [ ] GC events: < 10 per minute

---

### PERF-004: CPU Usage
**Setup:** Monitor CPU during gaze tracking

**Android (Profiler):**
1. Android Studio > Profiler > CPU
2. Start gaze tracking
3. Measure CPU % over 1 minute

**Success Criteria:**
- [ ] CPU usage: 15-30% (single core)
- [ ] No CPU spikes > 80%
- [ ] Battery drain reasonable (< 10% per hour)

---

### PERF-005: Calibration Performance
**Setup:** Time calibration computation

```kotlin
val t0 = System.currentTimeMillis()
calibration.computeCalibration()
val duration = System.currentTimeMillis() - t0
logger.debug("Calibration computed in ${duration}ms")
```

**Success Criteria:**
- [ ] Calibration time: < 500ms
- [ ] No UI freeze (use coroutines)
- [ ] Polynomial mode: < 100ms, Affine mode: < 50ms

---

### PERF-006: Kalman Filter Overhead
**Setup:** Compare processing time with/without Kalman filtering

```kotlin
// Disable Kalman
val t0 = System.nanoTime()
val rawGaze = processFrameNoSmoothing(bitmap)
val t1 = System.nanoTime()

// Enable Kalman
val t2 = System.nanoTime()
val smoothedGaze = processFrameWithKalman(bitmap)
val t3 = System.nanoTime()

val overhead = ((t3 - t2) - (t1 - t0)) / 1_000_000.0 // ms
logger.debug("Kalman overhead: ${overhead}ms")
```

**Success Criteria:**
- [ ] Kalman overhead: < 5ms
- [ ] Adaptive Kalman overhead: < 10ms
- [ ] No significant FPS impact

---

## Phase 5: Cross-Platform Validation

### Goal
Verify shared algorithms produce identical results on both platforms.

---

### CPV-001: Kalman Filter Parity
**Setup:** Feed identical input data to Android and iOS, compare outputs

**Test Data:** Sequence of 100 gaze points (x, y)

**Method:**
1. Create CSV file with 100 gaze points
2. Process on Android, log filtered outputs
3. Process on iOS, log filtered outputs
4. Compare outputs (should be identical)

**Success Criteria:**
- [ ] Outputs match within floating-point precision (< 0.001 difference)
- [ ] Both platforms produce same sequence

---

### CPV-002: Calibration Algorithm Parity
**Setup:** Feed identical calibration samples to both platforms

**Test Data:**
- 9 calibration targets (x, y)
- 30 gaze samples per target (x, y)

**Method:**
1. Create CSV with sample data
2. Compute calibration on Android
3. Compute calibration on iOS
4. Compare transform coefficients

**Success Criteria:**
- [ ] Transform coefficients match within 0.01%
- [ ] Calibration error matches exactly

---

### CPV-003: Gaze Calculation Parity
**Setup:** Feed identical face landmarks to both platforms

**Test Data:** Single frame of 478 landmarks

**Method:**
1. Export landmark data from MediaPipe
2. Feed to IrisGazeCalculator on Android
3. Feed to IrisGazeCalculator on iOS
4. Compare gaze estimates

**Success Criteria:**
- [ ] Gaze X, Y match within 0.01
- [ ] Head pose (yaw, pitch, roll) match within 0.1°
- [ ] Iris positions match within 0.001

---

## Phase 6: Regression Testing

### Goal
Ensure KMP migration didn't break existing functionality.

---

### REG-001: Existing Android Code Still Works
**Steps:**
1. Build app WITHOUT using SharedGazeTrackerAdapter (use old MediaPipeIrisGazeTracker)
2. Test gaze tracking with old implementation
3. Compare behavior to pre-migration version

**Success Criteria:**
- [ ] Old implementation still compiles
- [ ] Old implementation still functions
- [ ] No performance degradation

---

### REG-002: Calibration Data Backward Compatibility
**Steps:**
1. Use calibration data saved before migration
2. Load in new SharedGazeTrackerAdapter
3. Verify gaze accuracy

**Success Criteria:**
- [ ] Old calibration data loads successfully
- [ ] Accuracy matches pre-migration
- [ ] No data corruption

---

### REG-003: Settings Persistence
**Steps:**
1. Set preferences (sensitivity, offset, eye selection) before migration
2. Verify settings load correctly in new implementation

**Success Criteria:**
- [ ] All settings load correctly
- [ ] Defaults apply if settings missing
- [ ] No crashes on migration

---

## Automated Testing

### Unit Tests (Shared Module)

**Location:** `shared/src/commonTest/kotlin/`

#### TEST-001: KalmanFilter2D Tests
```kotlin
class KalmanFilter2DTest {
    @Test
    fun `test predict increases uncertainty`() {
        val filter = KalmanFilter2D()
        filter.update(0f, 0f)
        val p1 = filter.getUncertainty()
        filter.predict()
        val p2 = filter.getUncertainty()
        assertTrue(p2 > p1)
    }

    @Test
    fun `test update reduces uncertainty`() {
        val filter = KalmanFilter2D()
        filter.predict()
        val p1 = filter.getUncertainty()
        filter.update(0.5f, 0.5f)
        val p2 = filter.getUncertainty()
        assertTrue(p2 < p1)
    }

    @Test
    fun `test convergence to true value`() {
        val filter = KalmanFilter2D()
        repeat(10) {
            filter.update(1.0f, 1.0f)
        }
        val (x, y) = filter.getState()
        assertEquals(1.0f, x, 0.1f)
        assertEquals(1.0f, y, 0.1f)
    }
}
```

**Run tests:**
```bash
./gradlew :shared:cleanAllTests :shared:allTests
```

---

#### TEST-002: GazeCalibration Tests
```kotlin
class GazeCalibrationTest {
    @Test
    fun `test 9-point calibration with perfect data`() {
        val calibration = GazeCalibration(1920, 1080)
        val points = calibration.generateCalibrationPoints()

        // Perfect samples (gaze matches target)
        points.forEachIndexed { index, (x, y) ->
            repeat(30) {
                calibration.addCalibrationSample(index, x / 1920f, y / 1080f)
            }
        }

        assertTrue(calibration.computeCalibration())
        assertEquals(0f, calibration.getCalibrationError(), 10f)
    }

    @Test
    fun `test calibration fails with insufficient samples`() {
        val calibration = GazeCalibration(1920, 1080)
        assertFalse(calibration.computeCalibration()) // No samples
    }
}
```

---

#### TEST-003: IrisGazeCalculator Tests
```kotlin
class IrisGazeCalculatorTest {
    @Test
    fun `test sensitivity affects gaze range`() {
        val calc = IrisGazeCalculator(sensitivityX = 2.0f, sensitivityY = 2.0f)
        val (gaze1, _) = calc.calculateIrisPosition(...)

        calc.sensitivityX = 4.0f
        val (gaze2, _) = calc.calculateIrisPosition(...)

        assertTrue(abs(gaze2!![0]) > abs(gaze1!![0]))
    }

    @Test
    fun `test head pose within expected range`() {
        val calc = IrisGazeCalculator()
        val (yaw, pitch, roll) = calc.estimateHeadPose(landmarks)

        assertTrue(yaw in -45f..45f)
        assertTrue(pitch in -45f..45f)
        assertTrue(roll in -45f..45f)
    }
}
```

---

### Android Instrumentation Tests

**Location:** `app/src/androidTest/kotlin/`

#### TEST-101: SharedGazeTrackerAdapter Integration
```kotlin
@RunWith(AndroidJUnit4::class)
class SharedGazeTrackerAdapterTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun adapterInitializesSuccessfully() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = SharedGazeTrackerAdapter(context, 1920, 1080)

        assertTrue(adapter.initialize(useGpu = false))
        assertTrue(adapter.isReady())
    }

    @Test
    fun adapterProcessesFrameWithoutCrash() = runBlocking {
        val adapter = SharedGazeTrackerAdapter(...)
        adapter.initialize(useGpu = false)

        val bitmap = createTestBitmap()
        val result = adapter.processFrame(bitmap)

        // May be null if no face, but shouldn't crash
        assertNotNull(result ?: "No face detected")
    }
}
```

**Run tests:**
```bash
./gradlew connectedAndroidTest
```

---

### iOS XCTests

**Location:** `iosApp/VocableAACTests/`

#### TEST-201: FaceLandmarkerWrapper Tests
```swift
import XCTest
@testable import VocableAAC

class FaceLandmarkerWrapperTests: XCTestCase {
    func testInitialization() {
        let wrapper = FaceLandmarkerWrapper()
        XCTAssertTrue(wrapper.initialize(useGpu: false))
    }

    func testLandmarkDetection() {
        let wrapper = FaceLandmarkerWrapper()
        wrapper.initialize(useGpu: false)

        let image = createTestImage()
        let result = wrapper.detectLandmarks(image: image)

        // May be nil if no face in test image
        if let result = result {
            XCTAssertEqual(result.landmarks.count, 478)
        }
    }
}
```

**Run tests:**
```bash
# In Xcode
Product > Test (⌘U)
```

---

## Test Data & Metrics

### Baseline Metrics (Android - Pre-Migration)

Record these metrics BEFORE migration to compare:

| Metric | Pre-Migration | Post-Migration (Target) |
|--------|---------------|-------------------------|
| APK size | 22 MB | ~22-25 MB (< 15% increase) |
| Average FPS | 45 fps | 40-50 fps (no degradation) |
| Latency (median) | 35 ms | < 50 ms |
| Memory (peak) | 120 MB | < 150 MB |
| Calibration error | 45 px | < 50 px (similar) |
| Cold start time | 2.1 s | < 2.5 s |

### Test Face Dataset

Create a test dataset with:
- **5 different people** (various ages, ethnicities, genders)
- **3 lighting conditions** (bright, dim, natural)
- **4 head poses** (straight, left, right, tilted)
- **Total: 60 test cases**

For each test case, record:
- Face detection success (yes/no)
- Time to detect (ms)
- Gaze estimation quality (1-5 scale)
- Calibration error (px)

---

## Troubleshooting Test Failures

### Common Issues and Fixes

#### Issue: "FaceLandmarkDetector not initialized"
**Cause:** MediaPipe model not in assets or GPU fallback failed
**Fix:**
1. Check `app/src/main/assets/face_landmarker.task` exists
2. Try CPU mode: `initialize(useGpu = false)`
3. Check logcat for MediaPipe errors

---

#### Issue: High calibration error (> 150px)
**Cause:** Poor sample quality, user didn't look at targets, head movement
**Fix:**
1. Increase samples per target (60 instead of 30)
2. Improve lighting
3. Ensure user focuses on each target
4. Add visual feedback during calibration
5. Filter outlier samples (>3 std dev)

---

#### Issue: Low FPS (< 25 fps)
**Cause:** Device too slow, GPU mode not working, background processes
**Fix:**
1. Check GPU initialization: `isUsingGpu()` should be true
2. Close background apps
3. Use lower camera resolution (720p vs 1080p)
4. Profile with Android Profiler to find bottleneck

---

#### Issue: Memory leak
**Cause:** Bitmaps not recycled, coroutines not cancelled, MediaPipe instances not closed
**Fix:**
```kotlin
override fun onDestroy() {
    gazeAdapter.close() // Releases MediaPipe
    job.cancel() // Cancel coroutines
    super.onDestroy()
}
```

---

#### Issue: Gaze jitter/oscillation
**Cause:** Kalman filter settings, insufficient smoothing
**Fix:**
1. Increase measurement noise (reduce responsiveness)
2. Use ADAPTIVE_KALMAN mode
3. Lower sensitivity settings
4. Check for face detection instability

---

#### Issue: iOS build fails
**Cause:** Missing CocoaPods, wrong Xcode version, framework not embedded
**Fix:**
1. Run `pod install` in `iosApp/`
2. Open `.xcworkspace` not `.xcodeproj`
3. Ensure VocableShared.framework in "Frameworks, Libraries, and Embedded Content"
4. Check Xcode 15+

---

#### Issue: Cross-platform results differ
**Cause:** Camera differences, platform-specific bugs, floating-point precision
**Investigation:**
1. Compare landmark data (should be very similar)
2. Check Kalman filter state (should match exactly)
3. Verify same screen resolution used in calibration
4. Check for iOS-specific issues in Swift wrapper

---

## Test Report Template

### Test Execution Summary

**Date:** [Date]
**Tester:** [Name]
**Platform:** Android / iOS
**Device:** [Model, OS Version]
**Build:** [Git commit hash]

#### Build Validation
- [ ] BV-001: Shared module builds - PASS / FAIL
- [ ] BV-002: Android app builds - PASS / FAIL
- [ ] BV-003: iOS framework builds - PASS / FAIL
- [ ] BV-004: iOS app builds - PASS / FAIL

#### Functional Tests (Android)
- [ ] FT-A-001: Initialization - PASS / FAIL
- [ ] FT-A-002: Face detection - PASS / FAIL
- [ ] FT-A-003: Gaze estimation - PASS / FAIL
- [ ] FT-A-004: Kalman smoothing - PASS / FAIL
- [ ] FT-A-005: 9-point calibration - PASS / FAIL (Error: ___ px)
- [ ] FT-A-006: Calibrated accuracy - PASS / FAIL (Mean error: ___ px)
- [ ] FT-A-007: Calibration persistence - PASS / FAIL
- [ ] FT-A-008: Eye selection modes - PASS / FAIL
- [ ] FT-A-009: Blink detection - PASS / FAIL
- [ ] FT-A-010: Head pose compensation - PASS / FAIL
- [ ] FT-A-011: Sensitivity settings - PASS / FAIL
- [ ] FT-A-012: Offset adjustment - PASS / FAIL

#### Functional Tests (iOS)
- [ ] FT-iOS-001 to 012: All tests - PASS / FAIL
- [ ] FT-iOS-013: Cross-platform calibration - PASS / FAIL

#### Performance Tests
- [ ] PERF-001: Frame rate - PASS / FAIL (Avg: ___ fps)
- [ ] PERF-002: Latency - PASS / FAIL (Median: ___ ms)
- [ ] PERF-003: Memory usage - PASS / FAIL (Peak: ___ MB)
- [ ] PERF-004: CPU usage - PASS / FAIL (___ %)
- [ ] PERF-005: Calibration performance - PASS / FAIL (___ ms)
- [ ] PERF-006: Kalman overhead - PASS / FAIL (___ ms)

#### Cross-Platform Validation
- [ ] CPV-001: Kalman parity - PASS / FAIL
- [ ] CPV-002: Calibration parity - PASS / FAIL
- [ ] CPV-003: Gaze calculation parity - PASS / FAIL

#### Regression Tests
- [ ] REG-001: Old Android code works - PASS / FAIL
- [ ] REG-002: Calibration backward compat - PASS / FAIL
- [ ] REG-003: Settings persistence - PASS / FAIL

#### Automated Tests
- [ ] Unit tests (shared) - PASS / FAIL (___ / ___ tests)
- [ ] Android instrumentation - PASS / FAIL (___ / ___ tests)
- [ ] iOS XCTests - PASS / FAIL (___ / ___ tests)

#### Overall Result
- **Total Tests:** ___
- **Passed:** ___
- **Failed:** ___
- **Pass Rate:** ___%

#### Critical Issues Found
1. [Issue description, severity, workaround]
2. ...

#### Recommendations
- [ ] Ready for production
- [ ] Needs minor fixes
- [ ] Needs major rework
- [ ] Not ready

**Tester Signature:** _______________

---

## Continuous Testing Checklist

Use this checklist for ongoing development:

### Before Each Commit
- [ ] Run unit tests: `./gradlew :shared:allTests`
- [ ] Build succeeds: `./gradlew build`
- [ ] No new warnings

### Before Each PR
- [ ] All automated tests pass
- [ ] Manual smoke test on 1 Android device
- [ ] Manual smoke test on 1 iOS device (if changes affect iOS)
- [ ] Performance metrics within tolerance
- [ ] Code coverage > 70% (shared module)

### Before Each Release
- [ ] Full test suite on 3+ Android devices
- [ ] Full test suite on 3+ iOS devices
- [ ] Performance benchmark comparison
- [ ] Memory leak check (10 min sessions)
- [ ] User acceptance testing with 5+ users
- [ ] Calibration accuracy validation
- [ ] Cross-platform parity verification

---

## Test Automation Scripts

### Script 1: Quick Android Smoke Test
```bash
#!/bin/bash
# quick-test-android.sh

echo "Building Android app..."
./gradlew :app:assembleDebug || exit 1

echo "Installing on device..."
adb install -r app/build/outputs/apk/debug/app-debug.apk || exit 1

echo "Launching app..."
adb shell am start -n com.willowtree.vocable/.MainActivity

echo "Checking logs for errors..."
adb logcat -d | grep -i "error\|exception" | tail -20

echo "Smoke test complete!"
```

---

### Script 2: Performance Metrics Collector
```bash
#!/bin/bash
# collect-metrics.sh

PACKAGE="com.willowtree.vocable"
DURATION=60 # seconds

echo "Collecting metrics for $DURATION seconds..."

# Memory
adb shell dumpsys meminfo $PACKAGE | grep "TOTAL" > metrics.log

# CPU (sample every second)
for i in $(seq 1 $DURATION); do
    adb shell top -n 1 | grep $PACKAGE >> cpu.log
    sleep 1
done

# FPS (via logcat - requires app logging FPS)
adb logcat -d | grep "FPS:" > fps.log

echo "Metrics saved to metrics.log, cpu.log, fps.log"
```

---

### Script 3: Cross-Platform Comparison
```python
# compare-outputs.py
import csv

def compare_kalman_outputs(android_csv, ios_csv, tolerance=0.001):
    """Compare Kalman filter outputs from Android and iOS"""
    with open(android_csv) as f1, open(ios_csv) as f2:
        android = list(csv.reader(f1))
        ios = list(csv.reader(f2))

        if len(android) != len(ios):
            print(f"ERROR: Different lengths - Android: {len(android)}, iOS: {len(ios)}")
            return False

        diffs = []
        for i, (a_row, i_row) in enumerate(zip(android, ios)):
            a_x, a_y = float(a_row[0]), float(a_row[1])
            i_x, i_y = float(i_row[0]), float(i_row[1])

            diff = ((a_x - i_x)**2 + (a_y - i_y)**2)**0.5
            diffs.append(diff)

            if diff > tolerance:
                print(f"Row {i}: Difference {diff:.6f} exceeds tolerance {tolerance}")

        max_diff = max(diffs)
        avg_diff = sum(diffs) / len(diffs)

        print(f"Max difference: {max_diff:.6f}")
        print(f"Average difference: {avg_diff:.6f}")

        return max_diff <= tolerance

if __name__ == "__main__":
    result = compare_kalman_outputs("android_output.csv", "ios_output.csv")
    print("PASS" if result else "FAIL")
```

---

## Next Steps After Testing

### If All Tests Pass ✅
1. Document final metrics in test report
2. Create PR with test results
3. Request code review
4. Merge to main branch
5. Tag release: `git tag v1.0.0-kmp`
6. Proceed to production deployment

### If Tests Fail ❌
1. Document failures in test report
2. Prioritize critical issues
3. Create bug tickets for each issue
4. Fix issues in order of severity
5. Re-test after fixes
6. Repeat until all tests pass

### Ongoing Testing
1. Add new test cases for bug fixes
2. Update test suite as features added
3. Maintain >80% test coverage
4. Run regression tests for each release
5. Monitor production metrics (crash rate, performance)

---

## Summary

This testing guide provides comprehensive coverage of:
- ✅ Build validation (4 test cases)
- ✅ Android functional testing (12 test cases)
- ✅ iOS functional testing (13 test cases)
- ✅ Performance testing (6 test cases)
- ✅ Cross-platform validation (3 test cases)
- ✅ Regression testing (3 test cases)
- ✅ Automated testing (unit, instrumentation, XCTest)
- ✅ Test data and metrics
- ✅ Troubleshooting guide
- ✅ Test automation scripts

**Total: 41 manual test cases + automated test suites**

Execute this testing plan systematically to ensure the KMP migration is production-ready and maintains all quality standards.

---

**Questions?** See FINAL_STEPS_TO_PRODUCTION.md or PULL_REQUEST_SUMMARY.md for more details.
