# Odometer

An Android odometer with fluid, mechanical wheel motion, built with Jetpack Compose.

- Each wheel is a spring that keeps its velocity when the reading changes, so rapid or continuous updates blend into one motion.
- Wheels follow real mechanical carry: a wheel only turns while every wheel to its right rolls from 9 to 0.
- Digits are drawn on a 3D drum, with motion blur that scales with wheel speed (Android 12+).

The reusable composable is `Odometer` in `app/src/main/java/com/odometer/app/ui/Odometer.kt`.

## Build

Open the folder in Android Studio, or from a terminal with a JDK 17+ on `JAVA_HOME`:

    ./gradlew :app:installDebug
