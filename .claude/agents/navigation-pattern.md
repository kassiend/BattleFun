---
name: navigation-pattern
description: Navigation pattern guide for DentalCRM. Use when creating a new screen, route, or navigation flow. Describes where to add routes, how screens inject the navigator, and how result passing works.
---

# Navigation Pattern — DentalCRM

Navigation is built on **Navigation3** (`androidx.nav3`) with a custom abstraction layer.
Never use `NavBackStack` directly in screens — always go through `AppNavigator`.

## Key files

| File | Role |
|------|------|
| `navigation/Routes.kt` | All route definitions |
| `navigation/Navigator.kt` | `AppNavigator` interface |
| `navigation/NavigationResults.kt` | `NavigationResults` interface + `resultFlow` extension |
| `navigation/AppNavDisplay.kt` | Route → Screen binding + serializer registration |
| `navigation/NavigationModule.kt` | Koin DI (navigator & results are singletons) |

---

## 1. Adding a new route

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

### Screen with parameters AND result (detail/edit screens that return data)

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

**Rule:** Add `RESULT_KEY` + `Result` only when the parent screen needs to know what happened (e.g. refresh a list after edit).

---

## 2. Register route in AppNavDisplay.kt

Two places must be updated every time a new route is added:

### A — SavedStateConfiguration (serializer registry)
```kotlin
// AppNavDisplay.kt — inside polymorphic(NavKey::class) { ... }
subclass(EditFooRoute::class, EditFooRoute.serializer())
```
Forgetting this causes a crash on process restore / back-stack serialization.

### B — entryProvider (route → composable mapping)
```kotlin
// AppNavDisplay.kt — inside entryProvider { ... }
entry<EditFooRoute> { route ->
    EditFooScreen(fooId = route.fooId)
}
```

---

## 3. Screen implementation

### Inject navigator (all screens)
```kotlin
@Composable
fun EditFooScreen(fooId: Int) {
    val navigator: AppNavigator = koinInject()
    // ...
}
```

### Inject results (only screens that RECEIVE results from child screens)
```kotlin
@Composable
fun FooListScreen() {
    val navigator: AppNavigator = koinInject()
    val results: NavigationResults = koinInject()
    // ...
}
```

### Never pass navigator as a parameter — always inject via koinInject().

---

## 4. Navigation calls

```kotlin
navigator.navigate(EditFooRoute(fooId = 42))          // push screen
navigator.navigateUp()                                  // pop (back)
navigator.navigateAndClearBackStack(MainRoute)          // clear stack, set root
navigator.popUpTo(FooListRoute, inclusive = false)      // pop up to known screen
navigator.navigateBackWithResult(                       // pop + send result
    key = EditFooRoute.RESULT_KEY,
    result = EditFooRoute.Result(fooId = 42, updated = true)
)
```

---

## 5. Receiving results (parent / list screen)

```kotlin
LaunchedEffect(results) {
    results.resultFlow<EditFooRoute.Result>(EditFooRoute.RESULT_KEY)
        .collect { result ->
            if (result.updated) viewModel.refresh()
            results.clear(EditFooRoute.RESULT_KEY)  // MUST clear to avoid re-delivery
        }
}
```

**Important:** Always call `results.clear(key)` after handling. Results use replay semantics — they survive recomposition.

---

## 6. Checklist for a new screen

- [ ] Route added to `Routes.kt` (with `Result` if needed)
- [ ] Route registered in `SavedStateConfiguration` in `AppNavDisplay.kt`
- [ ] Route registered in `entryProvider` in `AppNavDisplay.kt`
- [ ] Screen uses `koinInject()` for `AppNavigator` (and `NavigationResults` if needed)
- [ ] Parent screen listens for result with `LaunchedEffect` + `results.clear()`
- [ ] ViewModel added to Koin module if new ViewModel created

---

## 7. When to use which method

| Scenario | Method |
|----------|--------|
| Open detail/edit from list | `navigator.navigate(Route)` |
| Go back without result | `navigator.navigateUp()` |
| Save/submit and return data | `navigator.navigateBackWithResult(key, result)` |
| Login success → main app | `navigator.navigateAndClearBackStack(MainRoute)` |
| Tab re-selection (pop to tab root) | `navigator.popUpTo(TabRoute, inclusive = false)` |

---

## 8. Tab navigation (MainScreen pattern)

Tabs are managed inside `MainScreen` using a nested back stack. Tab switching uses `popUpTo` to avoid stacking duplicate tab roots. Look at `MainScreen.kt` as the reference implementation for tab navigation.
