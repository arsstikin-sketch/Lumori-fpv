# FPV Drone для Minecraft 26.3

FPV-дрон в Minecraft: летаешь с настоящего пульта (или с клавиатуры) с физикой как в Liftoff, а другие игроки видят твой дрон — или птицу/моба вместо него.

| Папка | Что это | Куда ставить |
|---|---|---|
| [`mod/`](mod) | мод **FPV Drone** (Fabric) | у пилотов; можно и на Fabric-сервер |
| [`plugin/`](plugin) | плагин **FpvDrone** (Paper) | на Paper-сервер |
| [`docs/`](docs) | описание версий и дизайна | — |

## Быстрый старт
- **Пилот:** Fabric Loader + `fabric-api` + `fpvdrone-<версия>.jar` в `mods`. По желанию `modmenu`.
- **Paper-сервер:** `fpvdrone-plugin-<версия>.jar` в `plugins`.
- **Игроки без мода** ничего не ставят: дрон, птицы и мобы им видны.

## Сборка (нужен JDK 25)
```
cd mod     && ./gradlew build     # mod/build/libs/
cd plugin  && ./gradlew build     # plugin/build/libs/
```
На Windows: `gradlew.bat build` или `СОБРАТЬ.bat` в папке плагина.

## Клавиши мода (по умолчанию)
`R` — арм/дизарм · `V` — вид «я/дрон» · `Backspace` — убрать дрон · `H` — новый дрон · `K` — настройка пульта · `O` — настройки.

## Команды плагина
`/flyskin` — выбрать, кем выглядит дрон (птицы и мобы) · `/flyskin admin` — панель админа · `/drone ...` — цвета, тест, перезагрузка.

## Для разработчиков и ИИ-помощников
Правила, протокол мод ↔ сервер и ограничения Minecraft описаны в [AGENTS.md](AGENTS.md). История изменений — в [CHANGELOG.md](CHANGELOG.md).
