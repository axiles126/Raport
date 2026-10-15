# Report 2.6 (17) — відновлені вихідники

## ./ — повний Gradle-проєкт, відновлений вручну (~7.9k рядків Kotlin)
- core/  — ReportBuilder, Mgrs, TimeFmt, Coords, PilotStatus, AutoFlightTracker, Loss, ReportInput (перевірено kotlinc 2.0.21 на стабах; MGRS: NYC → 18T WL 83959 07351)
- data/  — @Serializable моделі + Store (prefs "kapibara", ключі kap_* описані в KDoc)
- sync/  — Op (34 типи), Msg, SharedState, Proto/SecureChannel (протокол у KDoc), NsdHelper, NetUtil, SyncManager
- alarm/, auto/ (PilotWatchService, TemplateFab), overlay/ (OverlayService, FlightBridge)
- ui/ReportViewModel.kt, MainActivity.kt, AndroidManifest.xml, res/xml, build.gradle.kts
- ui/ — MainScreen, Editor (EditorSection, ShiftSettings, Fields/Records, Events, TextPad, DayNightSummary),
  Dialogs (Settings, Alarm, Schedule, AutoDji, Network+QR, Guide), Widgets, MapScreen (osmdroid + ArcGisSat),
  Compass, LossScreen, Needs, ReportPad, NumberedParagraphs, theme/Theme
- settings.gradle.kts, gradle.properties, mipmap-іконки з APK

Не перевірялось компіляцією (нема Android SDK/Maven у середовищі) — сигнатури між модулями звірені вручну.
Gradle wrapper не включено: `gradle wrapper` або відкрити в Android Studio.

`// TODO(decomp)` (50 шт.) — місця, де імена/розбиття відновлені за контекстом (R8 інлайнив), логіка перенесена дослівно.
Версії ktor/serialization/osmdroid/zxing у build.gradle.kts — припущення (APK їх не зберігає); AndroidX/Compose — точні з META-INF.

## java-jadx/ — не в репо (див. архів Report-src.zip)
_merged-into-libs/ — бібліотечні класи, куди R8 злив код застосунку
(ReportBuilder → kotlin/reflect/TypesJVMKt, лямбди sync/VM → kotlinx/io/SourcesJvmKt$$ExternalSyntheticLambda0, FlightBridge → io/ktor/util/TextKt тощо).

## Протокол синхронізації (коротко)
ws://<hub>:8777/sync, NSD `_kapibara._tcp.`. N2:nonceC → N2:nonceH → E2:Hello(code) → Welcome + Snapshot.
base = SHA-256("REPORTSYNC2|"+code); key c2h/h2c = HMAC-SHA256(base, dir‖nC‖nH); AES-256-GCM,
кадр E2:base64(ctr8‖ct‖tag), IV = 0000‖ctr, лічильник строго зростає.

## Збірка й перевірка (CI)
`.github/workflows/build.yml` на кожен push у `decomp`: `assembleDebug` + `assembleRelease` (R8, `-dontobfuscate`, як в оригіналі),
потім емулятор API 35 проганяє однаковий сценарій на оригінальному APK (гілка `orig-apk`) і на відновленому
(головний екран → взліт → посадка → «Доба» → налаштування → меню). Результат (APK, лог, скріншоти, crash-логи) — у гілці `ci-log`.
Перший прогін: 0 помилок компіляції, 0 падінь, UI-дерева й скріншоти збігаються (відрізняються лише часові мітки).
Release підписаний debug-ключем — поверх оригіналу (ключ CN=Report) не встановиться, потрібне видалення старого.

## Зміни відносно оригіналу 2.6
- applicationId `io.github.axiles126.report` (код/namespace лишились `com.example.report` — протокол синхронізації серіалізує імена класів, тож сумісний з телефонами на оригіналі).
- `usesCleartextTraffic=false` + `network_security_config`. LAN-синхронізація (Ktor CIO, власне AES-GCM) не зачеплена — перевіряється в CI (`sync hub_got=N2:…`).
- Прибрано `USE_EXACT_ALARM`; при першому ввімкненні розкладу відкривається системний перемикач «Будильники й нагадування».
- Підпис release: власний ключ (CN=Report), секрети CI; `tools/set-signing-secrets.sh`.
