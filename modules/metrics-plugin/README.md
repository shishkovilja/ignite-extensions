# Плагин метрик Apache Ignite

Плагин добавляет на узел Ignite дополнительные метрики. Они экспортируются так же, как встроенные: через JMX,
OpenCensus, SQL-представление, `control.sh --metric` и т.д.

Каждая возможность плагина включается отдельно, по умолчанию все выключены:

| Возможность                                                            | Свойство               |
|------------------------------------------------------------------------|------------------------|
| [Экспорт конфигурации узла в метрики](#экспорт-конфигурации-в-метрики) | `configMetricsEnabled` |

## Установка

1. Добавьте JAR-файл `ignite-metrics-plugin` в каталог `libs` каждого узла.
2. Добавьте плагин в `pluginProviders` и включите нужные возможности.

Java:

```java
IgniteConfiguration cfg = new IgniteConfiguration()
    .setPluginProviders(new MetricsPluginProvider(
        new MetricsPluginConfiguration()
            .setConfigMetricsEnabled(true)));
```

Spring XML:

```xml
<bean class="org.apache.ignite.configuration.IgniteConfiguration">
    <property name="pluginProviders">
        <list>
            <bean class="org.apache.ignite.MetricsPluginProvider">
                <constructor-arg>
                    <bean class="org.apache.ignite.MetricsPluginConfiguration">
                        <property name="configMetricsEnabled" value="true"/>
                    </bean>
                </constructor-arg>
            </bean>
        </list>
    </property>
</bean>
```

Чтобы метрики были видны снаружи узла, настройте
[экспортёр метрик](https://ignite.apache.org/docs/ignite2/latest/monitoring-metrics/new-metrics-system).

## Экспорт конфигурации в метрики

Показывает конфигурацию узла в системе мониторинга: например, чтобы сравнить настройки узлов или получить оповещение о
неверном параметре. Данные те же, что в системном представлении `SYS.CONFIGURATION`.

### Имена метрик

Имя корневого реестра: `configuration`

Вложенный объект конфигурации — отдельный реестр, его свойства — метрики этого реестра:

| Часть конфигурации       | Пример метрики                                                                  |
|--------------------------|---------------------------------------------------------------------------------|
| Конфигурация узла        | `configuration.IgniteInstanceName`                                              |
| Вложенный объект         | `configuration.DataStorageConfiguration.DefaultDataRegionConfiguration.MaxSize` |
| Элемент массива объектов | `configuration.CacheConfiguration_0.Name`                                       |

В JMX это MBean `name=configuration` и MBean группы `group=configuration`, например
`group=configuration,name="DataStorageConfiguration.DefaultDataRegionConfiguration"`.

### Что экспортируется

* Все свойства, которые можно задать в конфигурации узла.
* Объекты конфигурации из пакета `org.apache.ignite` раскрываются в реестры. Для своих классов перечислите их пакеты
  через запятую в системном свойстве `IGNITE_CONFIGURATION_VIEW_PACKAGES`. Остальные объекты, коллекции и массивы
  простых значений экспортируются строкой.
* Пароли и другие конфиденциальные свойства не экспортируются никогда.

### Типы метрик

| Тип свойства             | Тип метрики |
|--------------------------|-------------|
| `boolean`                | boolean     |
| `int`, `short`, `byte`   | integer     |
| `long`                   | long        |
| `double`, `float`        | double      |
| Любой другой             | string      |

Свойства, которые могут быть не заданы (например, `ClientMode` типа `Boolean`), экспортируются строкой, значение
`null` — «не задано». Экспортёры, не поддерживающие строки (например, OpenCensus), такие метрики пропускают.

Значения считываются один раз, при запуске узла.

### Экспорт части конфигурации

Используйте фильтр экспортёра. Фильтр действует на все метрики узла, поэтому остальные реестры нужно разрешить явно.
Пример: все метрики узла, а из конфигурации — только `DataStorageConfiguration`:

```java
jmxSpi.setExportFilter(new RegexpMetricFilter(
    "(?!configuration(\\.|$)).*|configuration\\.DataStorageConfiguration(\\..*)?"));
```

### Ошибки

Если конфигурацию не удалось прочитать, в лог пишется ошибка `Failed to register the configuration metrics`, и узел
запускается без этих метрик.
