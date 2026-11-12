# Odometer

A handlebar dashboard for Android, built with Jetpack Compose: a GPS speedometer plus total
and trip odometers with fluid, mechanical wheel motion.

- **Speed** comes from the GPS receiver's own speed measurement, shown on a 240° dial whose
  range grows from 60 to 240 km/h as you go faster (hold the dial to change it by hand).
- **Riding style**: the dial is green while speed changes gently (eco), amber when pushing, and
  red with a warning for sudden acceleration or hard braking. The whole rim of the screen glows
  in the same colour (red floods further in and flashes), so it reads in peripheral vision.
- **Distance** is that speed integrated over time, so the reading does not creep while parked.
  Total, trip, max, average and moving time are saved across restarts.
- **Sideways or upright**: the layout adapts to either, the screen stays on, system bars are
  hidden, and "Lock rotation" pins the screen so leaning the bike cannot flip it.
- **Glove-safe**: anything that changes data needs a long press (hold Trip to reset it, hold
  Total to set it to your bike's reading).
- **Wheels**: each digit wheel is a spring that keeps its velocity between GPS fixes, follows
  real mechanical carry, and is drawn on a 3D drum with speed-based motion blur (Android 12+).

Speed and distance are only tracked while the app is on screen.

## Code

- `RideTracker.kt` - GPS fixes to speed and distance, persistence
- `ui/DashboardScreen.kt` - landscape and portrait layouts
- `ui/Speedometer.kt` - the dial
- `ui/Odometer.kt` - the reusable rolling-wheel composable

## Build

Open the folder in Android Studio, or from a terminal with a JDK 17+ on `JAVA_HOME`:

    ./gradlew :app:installDebug
