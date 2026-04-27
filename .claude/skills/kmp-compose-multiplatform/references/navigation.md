# Navigation 3 — KMP + Compose Multiplatform

References: [Nav3 Overview](https://developer.android.com/guide/navigation/navigation-3) | [CMP Nav3 Docs](https://kotlinlang.org/docs/multiplatform/compose-navigation-3.html) | [CMP Nav3 Recipes](https://github.com/terrakok/nav3-recipes)

---

## What Changed from Nav2

Nav3 is a ground-up redesign, not a version bump. The mental model shift:

| Nav2 | Nav3 |
|---|---|
| Library-owned back stack | **You own the back stack** — it's a `SnapshotStateList` |
| `NavController` + `NavHost` | `NavDisplay` observes your list directly |
| String routes + `navArgument` | **Typed `NavKey` objects** — args are constructor params |
| `NavController.navigate(route)` | `navigator.navigate(MyRoute(...))` |
| `NavController.popBackStack()` | `navigator.navigateUp()` |
| `SavedStateHandle` for VM args | Inject the typed route object directly into the ViewModel |

---

## Dependencies

`libs.versions.toml`:

```toml
[versions]
# Core Nav3 (KMP: Android, iOS, Desktop, Web)
nav3-ui                  = "1.0.0-alpha05"   # org.jetbrains.androidx.navigation3
# Lifecycle + ViewModel for Nav3
cmp-lifecycle            = "2.10.0-alpha05"
# Material3 Adaptive layouts for Nav3 (optional)
cmp-adaptive             = "1.3.0-alpha02"
# Browser history integration for web (optional, POC)
nav3-browser             = "0.2.0"

[libraries]
jetbrains-navigation3-ui         = { module = "org.jetbrains.androidx.navigation3:navigation3-ui",                         version.ref = "nav3-ui" }
jetbrains-lifecycle-vm-nav3      = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3",          version.ref = "cmp-lifecycle" }
jetbrains-adaptive-nav3          = { module = "org.jetbrains.compose.material3.adaptive:adaptive-navigation3",             version.ref = "cmp-adaptive" }
nav3-browser                     = { module = "com.github.terrakok:navigation3-browser",                                   version.ref = "nav3-browser" }
```

`build.gradle.kts` (commonMain):

```kotlin
commonMain.dependencies {
    implementation(libs.jetbrains.navigation3.ui)
    implementation(libs.jetbrains.lifecycle.vm.nav3)
    // Optional: adaptive layouts
    implementation(libs.jetbrains.adaptive.nav3)
    // Optional: web browser history
    implementation(libs.nav3.browser)
}
```

---

## Key Files (recommended project structure)

| File | Role |
|------|------|
| `navigation/Routes.kt` | All route definitions (`AppRoute` sealed interface + subclasses) |
| `navigation/AppNavigator.kt` | `AppNavigator` interface — hides raw back stack mutations |
| `navigation/NavigationResults.kt` | `NavigationResults` interface + `resultFlow` extension |
| `navigation/AppNavDisplay.kt` | Route → Screen binding + `SavedStateConfiguration` registration |
| `navigation/NavigationModule.kt` | Koin DI — navigator & results registered as singletons |

> **Rule:** Screens never touch `NavBackStack` directly. Always navigate through `AppNavigator`.

---

## 1. Adding a New Route

### Simple screen (no params, no result)

```kotlin
// Routes.kt
@Serializable
data object SettingsRoute : AppRoute
```

### Screen with parameters

```kotlin
// Routes.kt
@Serializable
data class PatientDetailRoute(val patientId: Int) : AppRoute
```

### Screen with parameters AND result

Add `RESULT_KEY` + a nested `Result` class only when a parent screen needs to react to what happened (e.g. refresh a list after an edit).

```kotlin
// Routes.kt
@Serializable
data class EditFooRoute(val fooId: Int) : AppRoute {
    companion object {
        const val RESULT_KEY = "edit_foo_result"
    }

    @Serializable
    data class Result(
        val fooId: Int,
        val updated: Boolean = false
    )
}
```

### Full single-module route file

```kotlin
// commonMain/navigation/Routes.kt
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute : NavKey

@Serializable data object HomeRoute          : AppRoute
@Serializable data object SettingsRoute      : AppRoute
@Serializable data object ConfirmDeleteRoute : AppRoute
@Serializable data object FilterRoute        : AppRoute

@Serializable
data class DetailRoute(val id: String) : AppRoute

@Serializable
data class ProfileRoute(val userId: String, val tab: String = "posts") : AppRoute

@Serializable
data class EditFooRoute(val fooId: Int) : AppRoute {
    companion object { const val RESULT_KEY = "edit_foo_result" }
    @Serializable data class Result(val fooId: Int, val updated: Boolean = false)
}
```

### Multi-module (aggregated sealed types)

```kotlin
// feature/auth/api — AuthRoutes.kt
@Serializable sealed interface AuthRoute : NavKey
@Serializable data object LoginRoute    : AuthRoute
@Serializable data object RegisterRoute : AuthRoute

val authSerializerModule = SerializersModule {
    polymorphic(NavKey::class) { subclassesOfSealed<AuthRoute>() }
}

// feature/home/api — HomeRoutes.kt
@Serializable sealed interface HomeRoute : NavKey
@Serializable data object HomeMainRoute                   : HomeRoute
@Serializable data class  HomeDetailRoute(val id: String) : HomeRoute

val homeSerializerModule = SerializersModule {
    polymorphic(NavKey::class) { subclassesOfSealed<HomeRoute>() }
}

// app module — SavedStateConfiguration combines them
private val navConfig = SavedStateConfiguration {
    serializersModule = authSerializerModule + homeSerializerModule
}
```

---

## 2. Register Route in AppNavDisplay.kt

Two places must be updated every time a new route is added.

### A — SavedStateConfiguration (serializer registry)

```kotlin
// AppNavDisplay.kt — inside polymorphic(NavKey::class) { … }
subclassesOfSealed<AppRoute>()
// or for individual registration:
subclass(EditFooRoute::class, EditFooRoute.serializer())
```

> Forgetting this causes a crash on process death / back-stack restoration on iOS and Web.

### B — entryProvider (route → composable)

```kotlin
// AppNavDisplay.kt
@Composable
fun AppNavDisplay() {
    val backStack = rememberNavBackStack(navConfig, HomeRoute)

    NavDisplay(
        backStack     = backStack,
        onBack        = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {

            entry<HomeRoute>          { HomeScreen() }
            entry<SettingsRoute>      { SettingsScreen() }
            entry<ConfirmDeleteRoute> { ConfirmDeleteDialog() }
            entry<FilterRoute>        { FilterBottomSheet() }

            entry<DetailRoute>  { route -> DetailScreen(id = route.id) }
            entry<ProfileRoute> { route -> ProfileScreen(userId = route.userId, initialTab = route.tab) }

            entry<EditFooRoute> { route -> EditFooScreen(fooId = route.fooId) }
        }
    )
}
```

> **Note on dialogs/sheets:** Nav3 has no `dialog {}` or `bottomSheet {}` DSL. Render `AlertDialog` / `ModalBottomSheet` inside a regular `entry<>`.

---

## 3. AppNavigator — Navigation Abstraction

Wrap raw back stack mutations behind an interface so screens stay decoupled from the stack.

```kotlin
// commonMain/navigation/AppNavigator.kt
interface AppNavigator {
    fun navigate(route: AppRoute)
    fun navigateUp()
    fun navigateAndClearBackStack(route: AppRoute)
    fun popUpTo(route: AppRoute, inclusive: Boolean = false)
    fun <T : Any> navigateBackWithResult(key: String, result: T)
}
```

Implementation:

```kotlin
// commonMain/navigation/AppNavigatorImpl.kt
class AppNavigatorImpl(
    private val backStack: SnapshotStateList<AppRoute>,
    private val navigationResults: NavigationResults
) : AppNavigator {

    override fun navigate(route: AppRoute) {
        backStack.add(route)
    }

    override fun navigateUp() {
        backStack.removeLastOrNull()
    }

    override fun navigateAndClearBackStack(route: AppRoute) {
        backStack.replaceAll(listOf(route))
    }

    override fun popUpTo(route: AppRoute, inclusive: Boolean) {
        val index = backStack.indexOfLast { it::class == route::class }
        if (index < 0) return
        val removeFrom = if (inclusive) index else index + 1
        if (removeFrom <= backStack.lastIndex) {
            backStack.subList(removeFrom, backStack.size).clear()
        }
    }

    override fun <T : Any> navigateBackWithResult(key: String, result: T) {
        navigationResults.set(key, result)
        backStack.removeLastOrNull()
    }
}
```

Koin DI — both are singletons so any screen can inject them:

```kotlin
// NavigationModule.kt
val navigationModule = module {
    single<SnapshotStateList<AppRoute>> { mutableStateListOf(HomeRoute) }
    single<AppNavigator>       { AppNavigatorImpl(get(), get()) }
    single<NavigationResults>  { NavigationResultsImpl() }
}
```

---

## 4. NavigationResults — Result Passing Between Screens

For passing data back from a child screen (e.g. edit → list). Uses `SharedFlow` with replay so results survive recomposition.

```kotlin
// commonMain/navigation/NavigationResults.kt
interface NavigationResults {
    fun <T : Any> set(key: String, result: T)
    fun <T : Any> getFlow(key: String): Flow<T>
    fun clear(key: String)
}

// Typed extension for use at call site
inline fun <reified T : Any> NavigationResults.resultFlow(key: String): Flow<T> =
    getFlow(key)
```

Implementation:

```kotlin
class NavigationResultsImpl : NavigationResults {
    private val results = mutableMapOf<String, MutableSharedFlow<Any>>()

    private fun flowFor(key: String) =
        results.getOrPut(key) { MutableSharedFlow(replay = 1) }

    override fun <T : Any> set(key: String, result: T) {
        flowFor(key).tryEmit(result)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getFlow(key: String): Flow<T> =
        flowFor(key) as Flow<T>

    override fun clear(key: String) {
        results[key]?.resetReplayCache()
    }
}
```

---

## 5. Screen Implementation

### Inject navigator (all screens)

```kotlin
@Composable
fun EditFooScreen(fooId: Int) {
    val navigator: AppNavigator = koinInject()
    // …
}
```

### Inject results (only screens that RECEIVE results from a child)

```kotlin
@Composable
fun FooListScreen() {
    val navigator: AppNavigator = koinInject()
    val results: NavigationResults = koinInject()
    // …
}
```

> **Never pass navigator as a composable parameter — always inject via `koinInject()`.**

---

## 6. Navigation Calls

```kotlin
navigator.navigate(EditFooRoute(fooId = 42))            // push screen
navigator.navigateUp()                                   // pop (back)
navigator.navigateAndClearBackStack(MainRoute)           // clear stack, set root
navigator.popUpTo(FooListRoute, inclusive = false)       // pop to known screen
navigator.navigateBackWithResult(                        // pop + send result
    key    = EditFooRoute.RESULT_KEY,
    result = EditFooRoute.Result(fooId = 42, updated = true)
)
```

### When to use which method

| Scenario | Method |
|----------|--------|
| Open detail/edit from list | `navigator.navigate(Route)` |
| Go back without result | `navigator.navigateUp()` |
| Save/submit and return data | `navigator.navigateBackWithResult(key, result)` |
| Login success → main app | `navigator.navigateAndClearBackStack(MainRoute)` |
| Tab re-selection (pop to tab root) | `navigator.popUpTo(TabRoute, inclusive = false)` |

---

## 7. Receiving Results (parent / list screen)

```kotlin
LaunchedEffect(results) {
    results.resultFlow<EditFooRoute.Result>(EditFooRoute.RESULT_KEY)
        .collect { result ->
            if (result.updated) viewModel.refresh()
            results.clear(EditFooRoute.RESULT_KEY)  // MUST clear — results have replay semantics
        }
}
```

> Always call `results.clear(key)` after handling. Without it the result re-delivers on recomposition.

---

## 8. Checklist for a New Screen

- [ ] Route added to `Routes.kt` (with nested `Result` + `RESULT_KEY` if returning data)
- [ ] Route registered in `SavedStateConfiguration` in `AppNavDisplay.kt`
- [ ] Route registered in `entryProvider` in `AppNavDisplay.kt`
- [ ] Screen uses `koinInject()` for `AppNavigator` (and `NavigationResults` if receiving results)
- [ ] Parent screen listens with `LaunchedEffect` + calls `results.clear(key)` after handling
- [ ] ViewModel added to Koin module if a new ViewModel is created

---

## Transitions

```kotlin
NavDisplay(
    backStack     = backStack,
    onBack        = { backStack.removeLastOrNull() },
    sceneStrategy = SinglePaneSceneStrategy(
        enterTransition    = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300)) },
        exitTransition     = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300)) },
        popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End,   tween(300)) },
        popExitTransition  = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End,  tween(300)) }
    ),
    entryProvider = entryProvider { /* … */ }
)
```

---

## Tab Navigation (per-tab back stacks)

Each tab owns its own `SnapshotStateList`. See `MainScreen.kt` as the reference implementation.

```kotlin
@Composable
fun MainScreen() {
    val homeStack    = rememberNavBackStack(navConfig, HomeMainRoute)
    val searchStack  = rememberNavBackStack(navConfig, SearchMainRoute)
    val profileStack = rememberNavBackStack(navConfig, ProfileMainRoute)

    var currentTab by rememberSaveable { mutableStateOf<TabRoute>(HomeTab) }

    val activeStack = when (currentTab) {
        HomeTab    -> homeStack
        SearchTab  -> searchStack
        ProfileTab -> profileStack
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(HomeTab, SearchTab, ProfileTab).forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick  = {
                            if (currentTab == tab) {
                                // Re-selection: pop to tab root via navigator
                                navigator.popUpTo(tab.rootRoute, inclusive = false)
                            } else {
                                currentTab = tab
                            }
                        },
                        icon  = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavDisplay(
            backStack     = activeStack,
            onBack        = { activeStack.removeLastOrNull() },
            modifier      = Modifier.padding(innerPadding),
            entryProvider = entryProvider {
                entry<HomeMainRoute>    { HomeScreen() }
                entry<HomeDetailRoute>  { route -> DetailScreen(id = route.id) }
                entry<SearchMainRoute>  { SearchScreen() }
                entry<ProfileMainRoute> { ProfileScreen() }
            }
        )
    }
}
```

---

## ViewModel Integration

Inject the typed route directly — no `SavedStateHandle` string lookups.

```kotlin
class DetailViewModel(
    private val route: DetailRoute,
    private val getItemUseCase: GetItemUseCase
) : ViewModel() {

    val uiState: StateFlow<DetailUiState> =
        getItemUseCase(route.id)
            .map { it.toUiState() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())
}

// Koin wiring:
val detailModule = module {
    viewModel { params -> DetailViewModel(route = params.get(), getItemUseCase = get()) }
}

// In entryProvider:
entry<DetailRoute> { route ->
    val vm: DetailViewModel = koinViewModel(parameters = { parametersOf(route) })
    DetailScreen(uiState = vm.uiState.collectAsStateWithLifecycle().value)
}
```

---

## Navigation from ViewModel (SharedFlow events)

Never call `AppNavigator` from a ViewModel. Emit events and handle them in the composable:

```kotlin
// ViewModel
class HomeViewModel : ViewModel() {
    private val _events = MutableSharedFlow<HomeEvent>()
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    fun onItemClicked(id: String) {
        viewModelScope.launch { _events.emit(HomeEvent.NavigateToDetail(id)) }
    }
}

sealed class HomeEvent {
    data class NavigateToDetail(val id: String) : HomeEvent()
    data object NavigateToSettings              : HomeEvent()
}

// Composable
@Composable
fun HomeScreen(viewModel: HomeViewModel = koinViewModel()) {
    val navigator: AppNavigator = koinInject()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.NavigateToDetail -> navigator.navigate(DetailRoute(event.id))
                HomeEvent.NavigateToSettings  -> navigator.navigate(SettingsRoute)
            }
        }
    }
}
```

---

## Back Navigation

### System back — `onBack` lambda (all platforms)

```kotlin
NavDisplay(
    backStack = backStack,
    onBack    = { backStack.removeLastOrNull() },
    …
)
```

### Intercept back with unsaved state

```kotlin
@Composable
fun HomeScreen(viewModel: HomeViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(enabled = uiState.hasUnsavedChanges) {
        viewModel.onBackPressed()  // show confirmation dialog
    }
}
```

### Custom back button in TopAppBar

```kotlin
@Composable
fun DetailScreen() {
    val navigator: AppNavigator = koinInject()
    Scaffold(
        topBar = {
            TopAppBar(
                title          = { Text("Detail") },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { /* content */ }
}
```

### Predictive Back (Android 14+)

```kotlin
@Composable
fun DetailScreen() {
    val navigator: AppNavigator = koinInject()
    var scale by remember { mutableFloatStateOf(1f) }

    PredictiveBackHandler { progress ->
        try {
            progress.collect { backEvent -> scale = 1f - (backEvent.progress * 0.1f) }
            navigator.navigateUp()
        } catch (e: CancellationException) {
            scale = 1f
        }
    }

    Box(Modifier.scale(scale)) { DetailContent() }
}
```

Enable in `AndroidManifest.xml`:
```xml
<application android:enableOnBackInvokedCallback="true" …>
```

---

## Deep Links

Nav3 has **no built-in URL matching**. Parse the URL into a typed route and push it via `AppNavigator`.

### Android

```kotlin
// androidMain/MainActivity.kt
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme { AppNavDisplay() }
            LaunchedEffect(intent) {
                intent?.data?.let { handleDeepLinkUri(it) }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { handleDeepLinkUri(it) }
    }
}

fun handleDeepLinkUri(uri: Uri, navigator: AppNavigator) {
    when (uri.pathSegments.firstOrNull()) {
        "detail"  -> uri.pathSegments.getOrNull(1)
            ?.let { navigator.navigate(DetailRoute(id = it)) }
        "profile" -> uri.pathSegments.getOrNull(1)
            ?.let { userId -> navigator.navigate(
                ProfileRoute(userId = userId, tab = uri.getQueryParameter("tab") ?: "posts")
            )}
    }
}
```

```xml
<!-- AndroidManifest.xml -->
<activity android:name=".MainActivity">
    <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="https" android:host="myapp.com" />
        <data android:scheme="myapp" />
    </intent-filter>
</activity>
```

### iOS

```swift
// iOSApp.swift
@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    DeepLinkHandlerKt.handleDeepLink(url: url.absoluteString)
                }
        }
    }
}
```

```kotlin
// commonMain — DeepLinkHandler.kt
object DeepLinkHandler {
    var onDeepLink: ((String) -> Unit)? = null
    fun handleDeepLink(url: String) { onDeepLink?.invoke(url) }
}
// Wire in iosMain before NavDisplay renders:
// DeepLinkHandler.onDeepLink = { rawUrl -> navigator.navigate(parseRoute(rawUrl)) }
```

### Web — browser history (optional POC)

```kotlin
NavDisplay(
    backStack      = backStack,
    onBack         = { backStack.removeLastOrNull() },
    localProviders = listOf(BrowserNavigationProvider(backStack)),
    entryProvider  = entryProvider { /* … */ }
)
```

### Deep link validation

Validate in the ViewModel constructor — fail fast:

```kotlin
class DetailViewModel(private val route: DetailRoute) : ViewModel() {
    init {
        require(route.id.isNotBlank() && route.id.length <= 64) {
            "Invalid item ID: ${route.id}"
        }
    }
}
```

ADB test:
```bash
adb shell am start -a android.intent.action.VIEW \
  -d "https://myapp.com/detail/123" com.example.myapp
```

---

## Cross-Module Navigation Contracts

Features expose routes and serializers in their `:api` module. The app module wires everything together.

```kotlin
// feature/auth/api — AuthNavigation.kt
interface AuthNavigation {
    val startRoute: AppRoute get() = LoginRoute
    fun EntryProviderBuilder<AppRoute>.authEntries()
}

// feature/auth/impl — AuthNavigationImpl.kt
class AuthNavigationImpl : AuthNavigation {
    override fun EntryProviderBuilder<AppRoute>.authEntries() {
        entry<LoginRoute>    { LoginScreen() }
        entry<RegisterRoute> { RegisterScreen() }
    }
}

// app/AppNavDisplay.kt
@Composable
fun AppNavDisplay(
    authNav: AuthNavigation = get(),
    homeNav: HomeNavigation = get()
) {
    val backStack = rememberNavBackStack(navConfig, authNav.startRoute)
    NavDisplay(
        backStack     = backStack,
        onBack        = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            with(authNav) { authEntries() }
            with(homeNav) { homeEntries() }
        }
    )
}
```

---

## Adaptive Layouts (multi-pane)

```kotlin
NavDisplay(
    backStack     = backStack,
    onBack        = { backStack.removeLastOrNull() },
    sceneStrategy = ListDetailSceneStrategy(),
    entryProvider = entryProvider {
        entry<HomeMainRoute> {
            ListDetailLayout(
                listContent   = { HomeList(onSelect = { id -> navigator.navigate(DetailRoute(id)) }) },
                detailContent = { /* shown inline on large screens */ }
            )
        }
        entry<DetailRoute> { route -> DetailScreen(id = route.id) }
    }
)
```

---

## Key Concepts Summary

- **Back stack = `SnapshotStateList<NavKey>`** — you own it, Compose observes it
- **Screens never touch the back stack directly** — use `AppNavigator` (injected via Koin)
- **Navigate forward** → `navigator.navigate(MyRoute(...))`
- **Navigate back** → `navigator.navigateUp()`
- **Clear to root** → `navigator.navigateAndClearBackStack(RootRoute)`
- **Return data to parent** → `navigator.navigateBackWithResult(key, result)` + `results.resultFlow<T>(key)` + `results.clear(key)`
- **No string routes, `navArgument`, or `SavedStateHandle` string lookups** — typed route objects only
- **Serialization required for KMP** — `SavedStateConfiguration` + `SerializersModule` for iOS/Web
- **Deep links are manual** — parse URL into a typed route and push via `navigator.navigate()`