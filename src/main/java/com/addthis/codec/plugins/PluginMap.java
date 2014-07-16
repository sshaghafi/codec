/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.addthis.codec.plugins;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.lang.reflect.Field;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Objects;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.Maps;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueFactory;
import com.typesafe.config.ConfigValueType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

public class PluginMap {

    private static final Logger log = LoggerFactory.getLogger(PluginMap.class);

    public static final PluginMap EMPTY = new PluginMap();

    @Nonnull private final Config config;
    @Nonnull private final BiMap<String, Class<?>> map;
    @Nonnull private final Map<String, String> aliases;
    @Nonnull private final Set<String> inlinedAliases;

    @Nonnull private final String category;
    @Nonnull private final String classField;

    @Nullable private final Class<?> baseClass;
    @Nullable private final Class<?> defaultSugar;
    @Nullable private final ConfigValue defaultOrigin;
    @Nullable private final Class<?> arraySugar;
    @Nullable private final String arrayField;
    @Nullable private final ConfigValue arrayOrigin;

    public PluginMap(@Nonnull String category, @Nonnull Config config) {
        this.config = config;
        this.category = checkNotNull(category);
        classField = config.getString("_field");
        boolean errorMissing = config.getBoolean("_strict");
        if (config.hasPath("_class")) {
            String baseClassName = config.getString("_class");
            try {
                baseClass = Class.forName(baseClassName);
            } catch (ClassNotFoundException e) {
                log.error("could not find specified base class {} for category {}",
                          baseClassName, category);
                throw new RuntimeException(e);
            }
        } else {
            baseClass = null;
        }
        Set<String> labels = config.root().keySet();
        BiMap<String, Class<?>> mutableMap = HashBiMap.create(labels.size());
        Map<String, String> mutableAliasMap = new HashMap<>();
        Set<String> mutableInlinedAliasSet = new HashSet<>();
        for (String label : labels) {
            if (label.charAt(0) == '_') {
                continue;
            }
            ConfigValue configValue = config.root().get(label);
            if (configValue.valueType() == ConfigValueType.STRING) {
                String className = (String) configValue.unwrapped();
                try {
                    Class<?> foundClass = findAndValidateClass(className);
                    mutableMap.put(label, foundClass);
                } catch (ClassNotFoundException maybeSwallowed) {
                    if (errorMissing) {
                        throw new RuntimeException(maybeSwallowed);
                    } else {
                        log.warn("plugin category {} with alias {} is pointing to missing class {}",
                                 category, label, className);
                    }
                }
            } else if (configValue.valueType() == ConfigValueType.OBJECT) {
                ConfigObject configObject = (ConfigObject) configValue;
                String className = configObject.toConfig().getString("_class");
                if (labels.contains(className)) {
                    // points to another alias
                    mutableAliasMap.put(label, className);
                } else {
                    try {
                        Class<?> foundClass = findAndValidateClass(className);
                        mutableMap.put(label, foundClass);
                    } catch (ClassNotFoundException maybeSwallowed) {
                        if (errorMissing) {
                            throw new RuntimeException(maybeSwallowed);
                        } else {
                            log.warn("plugin category {} with alias {} is pointing to missing class {}",
                                     category, label, className);
                        }
                    }
                }
                if (configObject.toConfig().hasPath("_inline") &&
                        configObject.toConfig().getBoolean("_inline")) {
                    mutableInlinedAliasSet.add(label);
                }
            } else {
                throw new ConfigException.WrongType(configValue.origin(), label,
                                                    "STRING OR OBJECT", configValue.valueType().toString());
            }
        }
        map = Maps.unmodifiableBiMap(mutableMap);
        aliases = Collections.unmodifiableMap(mutableAliasMap);
        checkAliasesForCycles();
        inlinedAliases = Collections.unmodifiableSet(mutableInlinedAliasSet);
        if (config.hasPath("_array")) {
            String arraySugarName = config.getString("_array");
            Class<?> configuredArraySugar = map.get(arraySugarName);
            arrayField = searchArraySugarFieldName(configuredArraySugar);
            if (arrayField != null) {
                arraySugar = configuredArraySugar;
                arrayOrigin = ConfigValueFactory.fromAnyRef(
                        arraySugarName, category + " array sugar : " +
                                        config.root().get("_array").origin().description());
            } else {
                arraySugar = null;
                arrayOrigin = null;
            }
        } else {
            arraySugar = null;
            arrayField = null;
            arrayOrigin = null;
        }
        if (config.hasPath("_default")) {
            String defaultName = config.getString("_default");
            defaultSugar = map.get(defaultName);
            defaultOrigin = ConfigValueFactory.fromAnyRef(
                    defaultName, category + " default type : " +
                                 config.root().get("_default").origin().description());
        } else {
            defaultSugar = null;
            defaultOrigin = null;
        }
    }

    private PluginMap() {
        config = ConfigFactory.empty();
        map = ImmutableBiMap.of();
        aliases = Collections.emptyMap();
        inlinedAliases = Collections.emptySet();
        classField = "class";
        category = "unknown";
        defaultSugar = null;
        defaultOrigin = null;
        baseClass = null;
        arraySugar = null;
        arrayField = null;
        arrayOrigin = null;
    }

    /** A thread safe, immutable bi-map view of this plugin map. */
    public BiMap<String, Class<?>> asBiMap() {
        return map;
    }

    @Nonnull public ConfigObject aliasDefaults(String alias) {
        ConfigValue configValue = config.root().get(alias);
        ConfigObject defaults;
        if ((configValue != null) && (configValue.valueType() == ConfigValueType.OBJECT)) {
            defaults = (ConfigObject) configValue;
        } else {
            defaults = ConfigFactory.empty().root();
        }
        String aliasTarget = aliases.get(alias);
        if (aliasTarget != null) {
            defaults = defaults.withFallback(aliasDefaults(aliasTarget));
        }
        return defaults;
    }

    @Nonnull public Set<String> inlinedAliases() {
        return inlinedAliases;
    }

    @Nonnull public String classField() {
        return classField;
    }

    @Nonnull public String category() {
        return category;
    }

    @Nullable public Class<?> arraySugar() {
        return arraySugar;
    }

    @Nullable public String arrayField() {
        return arrayField;
    }

    @Nullable public ConfigValue arrayOrigin() {
        return arrayOrigin;
    }

    @Nullable public Class<?> defaultSugar() {
        return defaultSugar;
    }

    @Nullable public ConfigValue defaultOrigin() {
        return defaultOrigin;
    }

    @Nullable public Class<?> baseClass() {
        return baseClass;
    }

    @Nonnull public String getClassName(Class<?> type) {
        String alt = map.inverse().get(type);
        if (alt != null) {
            return alt;
        } else {
            return type.getName();
        }
    }

    @Nonnull public Class<?> getClass(String type) throws ClassNotFoundException {
        Class<?> alt = map.get(type);
        if (alt != null) {
            return alt;
        } else {
            String aliasTarget = aliases.get(type);
            if (aliasTarget != null) {
                return getClass(aliasTarget);
            }
        }
        return findAndValidateClass(type);
    }

    @Nonnull private Class<?> findAndValidateClass(String className) throws ClassNotFoundException {
        Class<?> classValue = null;
        // if baseClass is defined, support shared parent package omission
        if (baseClass != null) {
            @Nullable String packageName = baseClass.getPackage().getName();
            while ((packageName != null) && (classValue == null)) {
                String packageSugaredName = packageName + '.' + className;
                try {
                    classValue = Class.forName(packageSugaredName);
                } catch (ClassNotFoundException ignored) {
                    int lastDotIndex = packageName.lastIndexOf('.');
                    if (lastDotIndex >= 0) {
                        packageName = packageName.substring(0, lastDotIndex);
                    } else {
                        packageName = null;
                    }
                }
            }
        }
        if (classValue == null) {
            classValue = Class.forName(className);
        }
        // if baseClass is defined, validate all aliased classes as being valid subtypes
        if ((baseClass != null) && !baseClass.isAssignableFrom(classValue)) {
            throw new ClassCastException(String.format(
                    "plugin %s specified a base class %s and '%s: %s', is not a valid subtype",
                    category, baseClass.getName(), classField, classValue.getName()));
        }
        return classValue;
    }

    @Override public String toString() {
        return Objects.toStringHelper(this)
                      .add("category", category)
                      .add("baseClass", baseClass)
                      .add("classField", classField)
                      .add("defaultSugar", defaultSugar)
                      .add("arraySugar", arraySugar)
                      .add("arrayField", arrayField)
                      .add("map", map)
                      .toString();
    }

    private void checkAliasesForCycles() {
        for (String key : aliases.keySet()) {
            Set<String> visited = new HashSet<>(aliases.size());
            checkAliasesForCyclesHelper(key, visited);
        }
    }

    private void checkAliasesForCyclesHelper(String key, Set<String> visited) {
        visited.add(key);
        String nextKey = aliases.get(key);
        if (nextKey == null) {
            // should mean it is present in the bimap
            return;
        }
        if (visited.contains(nextKey)) {
            throw new ConfigException.BadValue(config.root().get(key).origin(), key, "cyclical aliases detected");
        } else {
            checkAliasesForCyclesHelper(nextKey, visited);
        }
    }

    @Nullable private static String searchArraySugarFieldName(Class<?> arraySugar) {
        Class<?> clazzptr = arraySugar;
        while (clazzptr != null) {
            for (Field field : clazzptr.getDeclaredFields()) {
                Class<?> fieldType = field.getType();
                if (fieldType.isArray()
                    && fieldType.getComponentType().isAssignableFrom(arraySugar)) {
                    return field.getName();
                }
            }
            clazzptr = clazzptr.getSuperclass();
        }
        log.warn("failed to find an appropriate array field for class marked as array" +
                 "sugar: {}", arraySugar);
        return null;
    }
}
