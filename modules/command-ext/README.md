# Apache Ignite Command Extension

Добавляет в `control.sh` команду, которая сравнивает конфигурацию серверных узлов кластера и показывает только отличия.
Клиентские узлы не сравниваются.

```shell
control.sh --check-config [--only-diff]
```

Сейчас `--only-diff` — единственный флаг, с ним и без него вывод одинаковый.

Найденные отличия не влияют на код возврата: при успешном выполнении команда завершается с кодом `0`, даже если отличия
есть. В скриптах проверяйте строку `Total configuration differences`.

## Установка

Положите `ignite-command-ext-<version>.jar` в каталог `libs/` утилиты `control.sh` и **всех** серверных узлов, затем
перезапустите узлы.

Если jar нет на узле, к которому подключается утилита, команда завершится ошибкой. При настройках по умолчанию её текст
такой: `Compute grid functionality is disabled`.

## Как читать вывод

```
=== Total configuration differences: 2 ===

=== IgniteConfiguration: 1 found ===

--- FailureDetectionTimeout ---
Value    Nodes (consistentId / Host)    Count
10000    node1 / host1                      2
         node2 / host2
30000    node3 / host3                      1

=== IgniteSystemProperties: 1 found ===

--- IGNITE_EXCHANGE_HISTORY_SIZE ---
Value       Nodes (consistentId / Host)    Count
1000        node1 / host1                      2
            node2 / host2
<absent>    node3 / host3                      1

=== Plugins: 0 found ===
```

- На каждое различающееся свойство выводится отдельный блок. Свойства, одинаковые на всех узлах, не показываются.
- Строка таблицы — группа узлов с одним значением. Первой идёт самая большая группа, поэтому выделяющиеся узлы обычно
  находятся внизу блока.
- Узел показан как `<consistentId> / <имя хоста>`. Если узел слушает только loopback или задан `IGNITE_LOCAL_HOST`, вместо
  имени хоста выводится адрес.
- `<absent>` значит, что на узле свойство не задано. Пустые значения `[]` и `{}` считаются отличными от `<absent>`.
- Если сервер в кластере один, команда выводит `Nothing to compare: only 1 server node available.`

Кэш или регион данных, заданный только на части узлов, даёт отдельный блок на каждое своё свойство, то есть несколько
десятков блоков. Кэши, регионы данных и сервисы показываются по имени, а не по индексу.

## Что сравнивается

- **`IgniteConfiguration`** — конфигурация узла, с которой он запущен.
- **`IgniteSystemProperties`** — параметры Ignite, заданные через свойства JVM (`-D`) или переменные окружения. Значения
  берутся на момент запуска команды. Посторонние свойства JVM не сравниваются.
- **`Plugins`** — набор плагинов, их версии и классы.

## Что не сравнивается

- Свойства, которые у каждого узла свои. В `IgniteConfiguration` они отбираются по последней части имени, например
  `DiscoverySpi.LocalPort` исключено как `LocalPort`:
  `IgniteInstanceName`, `GridName`, `ConsistentId`, `NodeId`, `LocalHost`, `LocalAddress`, `LocalPort`, `Host`,
  `UserAttributes`, а также `MBeanServer` и `ExpiryPolicyFactory`.
- Системные свойства `IGNITE_OVERRIDE_CONSISTENT_ID`, `IGNITE_LOCAL_HOST`, `IGNITE_JETTY_HOST`, `IGNITE_SSH_HOST`,
  `IGNITE_TCP_DISCOVERY_ADDRESSES`, `IGNITE_DATA_CENTER_ID`, `IGNITE_SUCCESS_FILE`, `IGNITE_PROG_NAME`.
- Кэши, созданные динамически, и распределённые свойства кластера.
- Параметры JVM (`-Xmx`, GC), версии Ignite и Java.
- Настройки плагинов, кроме версии и класса.
- Тип реализации компонента: например, разные `FailureHandler` без настроек выглядят одинаково.

## Что учитывать

- Возможны ложные отличия. Значение пользовательского класса без `toString` показывается разным на всех узлах. Также
  могут различаться лямбды и порядок элементов в наборах.
- Лишний плагин на узле может дать ложные отличия в `PluginProviders[i]`, смотрите секцию `Plugins`.
- У массивов длиннее 100 элементов различия за этой границей не видны.
- Ошибка на одном узле прерывает всю команду, частичного отчёта нет.
- Узел, который ещё запускается, вызывает ошибку
  `Plugins system view is not registered yet, the node may be still starting, retry later`. Повторите команду после его
  старта.
- Узел, покинувший кластер во время выполнения команды, может просто не попасть в сравнение.
