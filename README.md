# PackRide 4.0

Android app for motorcycle GPS navigation — offline-first with optional online group features.

## Features

### Offline (no login required)
- Full-screen map powered by **MapLibre Android SDK** with local **MBTiles** tiles
- Offline turn-by-turn routing via **GraphHopper** (motorcycle profile)
- GPS tracking with background foreground service (`NavigationService`)
- GPX export

### Online (Supabase login)
- Shared group rides — same DB schema as the sibling PWA (`packride.cesena.biz`)
- Real-time rider positions
- Group messages (text + push-to-talk audio)

## Tech stack

| Layer | Technology |
|-------|-----------|
| UI | Kotlin + Jetpack Compose + Material 3 |
| Map | MapLibre Android SDK 11.5.1 |
| Offline routing | GraphHopper Core 9.1 |
| Backend | Supabase (supabase-kt BOM 3.0.3) |
| Local DB | Room 2.6.1 |
| DI | Hilt 2.51.1 |
| HTTP | Ktor 2.3.12 (Android engine) |
| State | Kotlin Coroutines + Flow + ViewModel |

## Package name
`biz.cesena.packride4`

## Setup

1. Copy `local.properties.example` to `local.properties` and fill in your Supabase credentials:
   ```
   SUPABASE_URL=https://<project-ref>.supabase.co
   SUPABASE_ANON_KEY=<anon-key>
   ```

2. (Optional) Place an OSM PBF file at `app/src/main/assets/maps/current.osm.pbf` for offline routing.

3. (Optional) Place MBTiles files under `<app-files-dir>/maps/` for offline tile rendering.

4. Open in Android Studio and run on a device with minSdk 26+.

## Build

```bash
./gradlew assembleDebug
# or for a signed release APK:
./gradlew assembleRelease
```

## Project structure

```
app/src/main/kotlin/biz/cesena/packride4/
├── MainActivity.kt            # Entry point (Hilt + Compose)
├── PackRideApp.kt             # @HiltAndroidApp
├── navigation/
│   └── AppNavigation.kt       # NavHost + bottom nav
├── ui/
│   ├── home/                  # MapLibre map + GPS
│   ├── mapmanager/            # Download/manage offline regions
│   ├── routing/               # GraphHopper destination + navigation
│   ├── rides/                 # Online group rides (Supabase)
│   ├── auth/                  # Supabase email/password login
│   ├── settings/              # App preferences (DataStore)
│   └── theme/                 # Material 3 colour scheme
├── data/
│   ├── local/                 # Room DB (MapRegion entity + DAO)
│   └── remote/                # SupabaseClientProvider
├── di/
│   └── AppModule.kt           # Hilt bindings
└── service/
    └── NavigationService.kt   # Foreground GPS service
```
