# Плагин metrics-plugin

Плагин предоставляет ряд дополнительных метрик.

## Установка и настройка

1. Добавьте JAR-файл `ignite-metrics-plugin` в каталог `libs` каждого узла.
2. Добавьте плагин в `pluginProviders` и включите нужные возможности.

Java:

```java
IgniteConfiguration cfg = new IgniteConfiguration()
    .setPluginProviders(new MetricsPluginProvider(
        new MetricsPluginConfiguration()
            .setConfigurationMetricsEnabled(true)));
```

Spring XML:

```xml
<bean class="org.apache.ignite.configuration.IgniteConfiguration">
    <property name="pluginProviders">
        <list>
            <bean class="org.apache.ignite.MetricsPluginProvider">
                <constructor-arg>
                    <bean class="org.apache.ignite.MetricsPluginConfiguration">
                        <property name="configurationMetricsEnabled" value="true"/>
                    </bean>
                </constructor-arg>
            </bean>
        </list>
    </property>
</bean>
```

## Экспорт конфигурации в метрики

Чтобы включить экспорт конфигурации узла в метрики, установите параметр
`MetricsPluginConfiguration#configurationMetricsEnabled` в `true`.

### Имена метрик

Имя корневого реестра: `configuration`

Вложенный объект конфигурации — отдельный реестр, его свойства — метрики этого реестра:

| Часть конфигурации       | Пример метрики                                                                  |
|--------------------------|---------------------------------------------------------------------------------|
| Конфигурация узла        | `configuration.IgniteInstanceName`                                              |
| Отдельная метрика        | `configuration.DataStorageConfiguration.DefaultDataRegionConfiguration.MaxSize` |
| Элемент массива          | `configuration.CacheConfiguration_0.Name`                                       |

В JMX это MBean `name=configuration` и MBean группы `group=configuration`, например
`group=configuration,name="DataStorageConfiguration.DefaultDataRegionConfiguration"`.

### Что экспортируется

Экспортируются все свойства конфигурации узла, доступные через системное представление `SYS.CONFIGURATION`.
Строка представления `A.B[0].C` становится метрикой `C` реестра `configuration.A.B_0`.

### Типы метрик

| Тип свойства             | Тип метрики |
|--------------------------|-------------|
| `boolean`                | boolean     |
| `int`, `short`, `byte`   | integer     |
| `long`                   | long        |
| `double`, `float`        | double      |
| Любой другой             | string      |

Значения считываются один раз, при запуске узла.
