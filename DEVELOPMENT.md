# Development

## Android application IDs

| Build type | Application ID |
| --- | --- |
| debug | `com.storyteller_f.dush.debug` |
| release | `com.storyteller_f.dush` |
| alpha | `com.storyteller_f.dush.alpha` |

The three builds can be installed together. Android gives each application ID its
own data directory, including the Room database, model files and preferences.
The namespace and Kotlin package names remain `com.storyteller_f.dush`.

Build with `./gradlew :androidApp:assembleDebug`, `:androidApp:assembleRelease`
or `:androidApp:assembleAlpha`. On Windows, use `./gradlew.bat`.

EasyLauncher adds a blue `DEBUG` ribbon to debug launcher icons and an orange
`ALPHA` ribbon to alpha launcher icons, including the round icons. Release icons
have no ribbon. Alpha uses the `-alpha` version-name suffix and the release
signing configuration when it is provided. CI builds alpha for main-branch
pushes and release for tags.

Alpha replaces the former daily build type and uses `.alpha` instead of `.daily`.
An installed daily build keeps its own data; alpha does not automatically migrate it.

The new debug application ID starts with a separate data directory. Existing data
under `com.storyteller_f.dush` is not automatically copied into the debug app.

The Appium test task installs debug and defaults to `com.storyteller_f.dush.debug`.
`APP_PACKAGE` can override the target package for an explicitly prepared build.
The default activity is the fully qualified `com.storyteller_f.dush.MainActivity`,
because its class package does not follow the application ID suffix.

## Room schema ownership

`AgentDatabase`, its entities, DAOs and migrations belong to `shared/commonMain`.
The Android and JVM KSP tasks use `shared/schemas` as the canonical schema history.
Keep all versioned JSON files there; versions 1, 2 and 3 and the existing
`MIGRATION_1_2` / `MIGRATION_2_3` migration chain remain unchanged.

For [issue #18](https://github.com/storytellerF/dush/issues/18), schema separation
by application build type was evaluated and is intentionally not configured:

- `shared` uses `com.android.kotlin.multiplatform.library`, whose
  [single-variant architecture](https://developer.android.com/kotlin/multiplatform/plugin#key-features-and-differences)
  does not support build types or product flavors. The application's debug,
  release and alpha variants consume the same compiled database definition.
- Schema JSON files are compile-time history, not runtime database files.
  Runtime isolation comes from the application IDs above; separate schema folders
  would not add runtime data isolation.
- Copying the same history into debug/release/alpha directories would create
  independently maintained copies without corresponding compilation variants.
  Selecting a schema directory from requested task names or a global property
  would also make combined builds depend on invocation details.

If database definitions actually diverge in future, first introduce explicit
compilation/module boundaries, seed each schema history from the existing files,
and define and verify migrations for each lineage. Do not discard historical
schemas or reset the database version to implement build isolation.

After changing the database, run `./gradlew :shared:kspAndroidMain :shared:kspKotlinJvm`
and review the generated schema changes. Build the Android variants together to
check their application IDs without changing the shared schema configuration:

```sh
./gradlew :androidApp:assembleDebug :androidApp:assembleRelease :androidApp:assembleAlpha
```
