# Floating Quiz Clicker

**Floating Quiz Clicker** is a lightweight native Kotlin Android app for Android 14 that shows a draggable floating overlay button above other apps and uses an `AccessibilityService` to perform two user-triggered taps at that button's screen position.

The app is designed around the quiz workflow where the user places the floating button over a **Start Quiz** button, presses **START COUNTDOWN**, moves the floating button to the final answer or submit button, and lets the app perform the second tap after a configured one-time delay.

> **Important precision note:** Android apps cannot guarantee hard real-time execution because input injection and message scheduling are still controlled by the Android framework, AccessibilityService gesture dispatch, device load, refresh timing, and the target app. This project now uses a dedicated high-priority timing thread, monotonic `SystemClock.uptimeMillis()`, an exact target deadline, and a short tap gesture duration to reduce drift. The second tap is dispatched at the floating button's live center as close as Android permits to the configured target time.

## Requirements

| Item | Value |
|---|---:|
| Language | Kotlin |
| UI style | Native Android views, no Flutter, no Compose |
| Minimum SDK | 26 |
| Target SDK | 34 |
| Compile SDK | 34 |
| Gradle files | Kotlin DSL |
| Tap mechanism | `AccessibilityService.dispatchGesture(...)` |
| Overlay mechanism | `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` |
| Delay range | `0.000` to `31.000` seconds |
| Delay precision | Milliseconds, parsed from seconds with three decimal places |

## Exact Runtime Workflow

The user opens Facebook or any other app and starts the floating overlay from Floating Quiz Clicker. The floating **TAP** button remains visible above other apps. The user drags the center of the **TAP** button over the **Start Quiz** button, enters a delay such as `21.000`, and presses **START COUNTDOWN** on the floating panel.

Immediately after **START COUNTDOWN** is pressed, the app dispatches one accessibility tap at the floating button's current center. The user can then drag the same floating button aside, manually answer the quiz, and move the floating button over the final answer or submit button. At the configured delay, measured from the immediate-tap dispatch start, the app dispatches the second accessibility tap at the **live current center** of the floating button. This aligns stopwatch-style tests more closely because the external timer usually starts from the first injected tap rather than from the panel button press.

## Permissions Required

The app requires two user-granted permissions. Android does not allow normal apps to silently grant either permission, so the app opens the correct settings screens and shows status in the main activity.

| Permission | Why It Is Needed |
|---|---|
| Display over other apps | Required to keep the floating button and control panel visible over Facebook or another app. |
| Accessibility service | Required to dispatch tap gestures at screen coordinates over another app. |
| Notifications on Android 13+ | Used for the foreground service notification while the overlay is running. |

## Building Locally

If you have Android Studio installed, open the repository root and run a standard Gradle sync. The project uses Android Gradle Plugin `8.5.2`, Kotlin `2.0.20`, Java 17, and Kotlin DSL build files.

```bash
# If you add a Gradle wrapper to the repository:
./gradlew assembleDebug

# Or build from Android Studio / Codemagic with an available Gradle installation:
gradle assembleDebug
```

## Codemagic

A minimal `codemagic.yaml` is included. It intentionally omits empty `android_signing` and `publishing.email.recipients` blocks because Codemagic rejects those fields when they are present as empty lists. It also does not specify `instance_type`, so Codemagic will use the default build machine available on your current billing plan. If your Codemagic image has Gradle available, it builds `assembleDebug`. If you prefer the Gradle wrapper workflow, generate a wrapper once locally with a compatible Gradle version and commit `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle/wrapper/gradle-wrapper.properties`.

## Repository Structure

```text
floating-quiz-clicker/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/floatingquizclicker/
│       │   ├── MainActivity.kt
│       │   ├── accessibility/TapAccessibilityService.kt
│       │   └── overlay/FloatingOverlayService.kt
│       └── res/
│           ├── drawable/ic_launcher_foreground.xml
│           ├── values/strings.xml
│           ├── values/styles.xml
│           └── xml/accessibility_service_config.xml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── codemagic.yaml
├── .gitignore
└── README.md
```

## Safety, Compatibility, and Usage Notes

This project performs only taps that are initiated by the user pressing **START COUNTDOWN**. It does not inspect Facebook content, read quiz answers, scrape data, bypass app protections, hide the overlay from other apps, or run unattended background automation. Use it only where you are allowed to automate taps and where it does not violate the rules of the app or service you are interacting with.

Some apps, embedded browsers, games, quiz platforms, banking apps, streaming apps, and protected WebViews may ignore injected AccessibilityService gestures, block overlays, detect overlays, or cancel simulated input for fraud prevention, game-integrity, accessibility-security, or anti-cheat reasons. This project does **not** attempt to bypass those protections. If a specific Facebook Lite quiz or website blocks overlay-based or accessibility-based input, there is no legitimate code change in this project that can guarantee operation inside that protected flow.

## Implementation Notes

The overlay button stays visible throughout the countdown. During each tap dispatch, the button window is temporarily marked as not touchable so the accessibility gesture can pass through to the app underneath while the button remains visible. The position used for the second tap is not pre-recorded; it is read from the button's current window coordinates at the exact scheduled callback time.

The floating panel uses two window modes. In passive mode, it does not take keyboard focus, which keeps interaction with the underlying app predictable. When the delay field is touched, the panel switches into input mode so the `EditText` can receive focus and the soft keyboard can appear. Pressing **START COUNTDOWN** hides the keyboard and switches the panel back to passive overlay mode before the immediate tap is dispatched.

For better timing consistency, the delayed tap is scheduled on a dedicated `HandlerThread` with urgent display priority. The code posts slightly before the target deadline, sleeps until the final millisecond window, and dispatches the tap at the exact monotonic deadline as closely as Android allows. The accessibility tap gesture duration is shortened to `10 ms` to reduce stopwatch-visible offset compared with the original longer gesture.
