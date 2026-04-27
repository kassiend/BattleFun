# Error Handling — DentalCRM

Этот файл описывает **реальный** паттерн обработки ошибок в проекте, а не абстрактные best practices.

---

## Уровни обработки ошибок

```
Ktor HTTP call
    └── NetworkClient.handle() / handleWithResult()
            └── Result<T>  (Success / Error / Failure)
                    └── stateHandle { } в ViewModel
                            ├── setState { copy(screenState = ...) }
                            ├── setErrorState { ViewError... }
                            └── SnackbarController.sendEvent(...)
```

---

## 1. Result — базовый тип ответа

Файл: `core/utils/Result.kt`

```kotlin
sealed interface Result<out R> {
    class Success<out R>(val value: R) : Result<R>
    data class Error(val code: Int?, val message: String?) : Result<Nothing>   // HTTP 4xx/5xx
    class Failure(val throwable: Throwable) : Result<Nothing>                  // сетевая/runtime ошибка
}
```

**Правило:** Репозитории возвращают `Result<T>`. Никогда не бросай исключения выше data layer.

---

## 2. NetworkClient — как Result создаётся

Файл: `core/network/NetworkClient.kt`

```kotlin
// Простой запрос
suspend inline fun <reified Data> HttpClient.handle(
    block: HttpRequestBuilder.() -> Unit
): Result<Data>

// С маппингом Data -> Domain
suspend inline fun <reified Data, reified Domain> HttpClient.handleWithResult(
    mapping: (Data?) -> Domain,
    block: HttpRequestBuilder.() -> Unit
): Result<Domain>
```

Маппинг исключений:
- `ClientRequestException` → `Result.Error(statusCode, message)` — HTTP 4xx/5xx
- Любой другой `Exception` → `Result.Failure(ex)` — сеть упала, таймаут, etc.
- `200 OK`, но не тот статус → `Result.Failure(Throwable("status: body"))` — нестандартные коды

---

## 3. ErrorContent — типизированный контент ошибки

Файл: `core/network/NetworkClient.kt`

```kotlin
sealed class ErrorContent {
    data class Dead(val statusCode: Int?, val code: String?, val title: String?, val message: String?) // фатальная, экран нельзя использовать
    data class Alert(val statusCode: Int?, val code: String?, val title: String?, val message: String?) // показать диалог/алерт
    data class Validation(val content: HashMap<String, String>?) // ошибки по полям формы
}
```

`ErrorContent` создаётся вручную в ViewModel при обработке `Result.Error`, когда нужно дать пользователю значимое сообщение.

---

## 4. stateHandle — обработка Result в ViewModel

Файл: `core/utils/Result.kt`

```kotlin
// Полный вариант
result.stateHandle(
    onError = { code, message ->
        setState { copy(screenState = ScreenState.Idle) }
        // опционально: SnackbarController.sendEvent(...)
    },
    onFailure = { throwable ->
        setState { copy(screenState = ScreenState.Idle) }
    },
    onSuccess = { data ->
        setState { copy(items = data, screenState = ScreenState.Idle) }
    }
)

// Краткий вариант — только success, остальное игнорируется
result.stateHandle { data ->
    setState { copy(items = data) }
}
```

**Важно:** Короткий `stateHandle { }` допустим только когда ошибку не нужно показывать пользователю (фоновая операция, не критично). Для форм и загрузок — всегда указывай `onError` + `onFailure`.

---

## 5. ScreenState — состояние загрузки

Файл: `core/ui/UiError.kt`

```kotlin
sealed interface ScreenState : ViewState {
    data object Loading : ScreenState      // показать оверлей с прогрессом
    data object Idle : ScreenState         // нормальное состояние
    data object Paginating : ScreenState   // пагинация (не блокирует экран)
}
```

Паттерн в ViewModel:
```kotlin
fun loadData() {
    setState { copy(screenState = ScreenState.Loading) }
    launch {
        repository.getData().stateHandle(
            onError = { _, _ -> setState { copy(screenState = ScreenState.Idle) } },
            onFailure = { setState { copy(screenState = ScreenState.Idle) } },
            onSuccess = { data -> setState { copy(data = data, screenState = ScreenState.Idle) } }
        )
    }
}
```

---

## 6. ViewError — ошибки, управляемые ViewModel

Файл: `core/base/ViewError.kt`

```kotlin
sealed class ViewError : ViewState {
    data class AlertError(val title: String?, val message: String?) : ViewError()          // диалог
    data class DeadError(val title: String?, val message: String?) : ViewError()         // экран мёртв
    data class ViewValidationError(val content: String?, val title: String?, val message: String?) : ViewError() // ошибки полей
    data object Unexpected : ViewError()                                                  // неожиданная
}
typealias UnknownViewError = ViewError.DeadError
```

В BaseStateViewModel доступно:
```kotlin
setErrorState { ViewError.AlertError(title = null, message = "Ошибка сети") }

// или через handleDefaultError (маппинг ErrorContent -> ViewError)
handleDefaultError(ErrorContent.Alert(code = null, title = null, message = "..."))
```

Для валидации — переопредели в ViewModel:
```kotlin
override fun handleValidationError(content: HashMap<String, String>) {
    // парсить поля и обновить state
}
```

---

## 7. SnackbarController — глобальный snackbar

Файл: `theme/SnackbarEvent.kt`

```kotlin
// Из ViewModel (в корутине):
SnackbarController.sendEvent(
    SnackbarEvent(
        message = "Запись создана",
        snackbarType = SnackbarEvent.SnackbarType.Green
    )
)

// Типы:
// SnackbarType.Green — успех
// SnackbarType.Red   — ошибка
// SnackbarType.Grey  — нейтральная информация
```

Подключается в App/корневом экране через `ObserveAsEvents(SnackbarController.events)`.

---

## 8. UiStateScaffold — обёртка экрана с загрузкой

Файл: `core/ui/UiState.kt`

```kotlin
UiStateScaffold(
    modifier = Modifier.fillMaxSize(),
    isLoading = state.screenState == ScreenState.Loading,
    uiError = null // UiError если нужно показать ошибку поверх контента
) {
    // содержимое экрана
}
```

---

## 9. ObserveAsEvents — подписка на side effects в Composable

Файл: `theme/ObserveAsEvents.kt`

```kotlin
// Для обычных событий
ObserveAsEvents(flow = viewModel.effect) { effect ->
    when (effect) {
        is MyViewSideEffect.ShowError -> { /* ... */ }
        is MyViewSideEffect.NavigateBack -> navigator.navigateUp()
    }
}

// collectLatest — отменяет предыдущий если новый пришёл раньше
ObserveLatestAsEvents(flow = viewModel.effect) { effect -> ... }
```

---

## Чеклист при добавлении нового запроса

- [ ] Репозиторий возвращает `Result<T>` через `client.handle<T> {}`
- [ ] ViewModel вызывает `stateHandle` с `onError` + `onFailure` для пользовательских операций
- [ ] Перед запросом: `setState { copy(screenState = ScreenState.Loading) }`
- [ ] В каждой ветке (`onError`, `onFailure`, `onSuccess`): сбросить `ScreenState.Idle`
- [ ] Ошибки показываются через `SnackbarController.sendEvent` или `setErrorState`
- [ ] Форма: `handleValidationError` переопределён если backend возвращает field-level ошибки
