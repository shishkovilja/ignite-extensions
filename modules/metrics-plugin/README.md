# Модуль метрик конфигурации Apache Ignite

Плагин Ignite, который предоставляет конфигурацию узла (`IgniteConfiguration`) в виде метрик. Они экспортируются
реализациями `MetricExporterSpi` (JMX, OpenCensus, лог, SQL-представление и т.д.) так же, как и любые другие метрики узла.

## Использование

Добавьте JAR-файл модуля в classpath каждого узла, например, в каталог `libs` дистрибутива Ignite.
Плагин загружается автоматически через `ServiceLoader`, изменять конфигурацию не нужно.

Ignite загружает плагины из classpath только если свойство `IgniteConfiguration#pluginProviders` не задано. В противном
случае добавьте `ConfigurationMetricsPluginProvider` в список провайдеров плагинов узла:

```java
new IgniteConfiguration()
    .setPluginProviders(new ConfigurationMetricsPluginProvider(), new SomeOtherPluginProvider());
```

или через Spring XML:

```xml

<bean class="org.apache.ignite.configuration.IgniteConfiguration">
  <property name="pluginProviders">
    <list>
      <bean class="org.apache.ignite.ConfigurationMetricsPluginProvider"/>
      <bean class="com.example.SomeOtherPluginProvider"/>
    </list>
  </property>
</bean>
```

Чтобы экспортировать только часть метрик конфигурации, задайте фильтр экспорта используемого metric exporter:

```java
JmxMetricExporterSpi jmxSpi = new JmxMetricExporterSpi();

jmxSpi.setExportFilter(new RegexpMetricFilter("configuration\\.DataStorageConfiguration(\\..*)?"));
```

## Имена метрик

Метрики повторяют строки системного представления `SYS.CONFIGURATION`: строка представления `A.B.C` становится метрикой
`C` в registry `configuration.A.B`. Например, максимальный размер региона данных по умолчанию — это метрика
`configuration.DataStorageConfiguration.DefaultDataRegionConfiguration.MaxSize`.

| Часть конфигурации                          | Предоставляется как                                                                          |
|---------------------------------------------|----------------------------------------------------------------------------------------------|
| `IgniteConfiguration`                       | реестр `configuration`                                                                       |
| Вложенный объект конфигурации               | реестр с именем по своему пути: `configuration.DataStorageConfiguration`                     |
| Объект конфигурации в массиве               | реестр с индексом элемента: `configuration.CacheConfiguration_1`                             |
| Любое другое свойство                       | метрика реестра владельца: `configuration.IgniteInstanceName`                                |

Индекс элемента отделяется подчёркиванием: то же самый элемент системное представление называет `CacheConfiguration[1]`.

При использовании `JmxMetricExporterSpi` корневой реестр — это MBean `name=configuration`, а вложенные реестры — MBeans
группы `group=configuration`, например `group=configuration,name="DataStorageConfiguration.DefaultDataRegionConfiguration"`.

Системное представление определяет, что именно экспонируется:

* Свойство — это пара публичного сеттера `setXxx` и публичного геттера `getXxx` или `isXxx`.
* Объект конфигурации — это объект класса из пакета `org.apache.ignite` или из пакетов, перечисленных в системном
  свойстве `IGNITE_CONFIGURATION_VIEW_PACKAGES`.
* Массив класса конфигурации, например `CacheConfiguration[]`, раскрывается поэлементно. Прочие массивы и все коллекции
  становятся одной метрикой.

## Значения метрик

Все метрики — это `String`-метрики с теми же значениями, что и в системном представлении, независимо от типа свойства.
Например, `MaxSize` равна `"134217728"`. Если свойство не задано, значение равно `null`.

Значения считываются один раз, при завершении запуска узла, поэтому метрики показывают конфигурацию, с которой узел
был запущен.
