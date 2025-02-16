/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.common;

import com.electronwill.nightconfig.core.*;
import com.electronwill.nightconfig.core.ConfigSpec.CorrectionAction;
import com.electronwill.nightconfig.core.ConfigSpec.CorrectionListener;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.electronwill.nightconfig.core.utils.UnmodifiableConfigWrapper;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.ObjectArrays;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.config.widgets.ConfigGuiWidget;
import net.minecraftforge.client.gui.config.widgets.ConfigGuiWidgetFactory;
import net.minecraftforge.fml.config.IConfigSpec;
import net.minecraftforge.fml.loading.LogMarkers;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.electronwill.nightconfig.core.ConfigSpec.CorrectionAction.*;
import static net.minecraftforge.fml.loading.LogMarkers.CORE;

/*
 * Like {@link com.electronwill.nightconfig.core.ConfigSpec} except in builder format, and extended to accept comments, language keys,
 * and other things Forge configs would find useful.
 */
public class ForgeConfigSpec extends UnmodifiableConfigWrapper<UnmodifiableConfig> implements IConfigSpec<ForgeConfigSpec>//TODO: Remove extends and pipe everything through getSpec/getValues?
{
    private final Map<List<String>, String> levelComments;
    private final Map<List<String>, String> levelTranslationKeys;

    private final UnmodifiableConfig values;
    private Config childConfig;
    private final boolean visibleOnModConfigScreen;

    private boolean isCorrecting = false;

    private ForgeConfigSpec(UnmodifiableConfig storage, UnmodifiableConfig values, Map<List<String>, String> levelComments, Map<List<String>, String> levelTranslationKeys, final boolean visibleOnModConfigScreen) {
        super(storage);
        this.values = values;
        this.levelComments = levelComments;
        this.levelTranslationKeys = levelTranslationKeys;
        this.visibleOnModConfigScreen = visibleOnModConfigScreen;
    }

    public String getLevelComment(List<String> path) {
        return levelComments.get(path);
    }

    public String getLevelTranslationKey(List<String> path) {
        return levelTranslationKeys.get(path);
    }

    public void setConfig(CommentedConfig config) {
        this.childConfig = config;
        if (config != null && !isCorrect(config)) {
            String configName = config instanceof FileConfig ? ((FileConfig) config).getNioPath().toString() : config.toString();
            // Forge Config API Port: replace with SLF4J logger
            LogUtils.getLogger().warn(CORE, "Configuration file {} is not correct. Correcting", configName);
            correct(config,
                    (action, path, incorrectValue, correctedValue) ->
                            // Forge Config API Port: replace with SLF4J logger
                            LogUtils.getLogger().warn(CORE, "Incorrect key {} was corrected from {} to its default, {}. {}", DOT_JOINER.join( path ), incorrectValue, correctedValue, incorrectValue == correctedValue ? "This seems to be an error." : ""),
                    (action, path, incorrectValue, correctedValue) ->
                            // Forge Config API Port: replace with SLF4J logger
                            LogUtils.getLogger().debug(CORE, "The comment on key {} does not match the spec. This may create a backup.", DOT_JOINER.join( path )));

            if (config instanceof FileConfig) {
                ((FileConfig) config).save();
            }
        }
        this.afterReload();
    }

    @Override
    public void acceptConfig(final CommentedConfig data) {
        setConfig(data);
    }

    public boolean isCorrecting() {
        return isCorrecting;
    }

    public boolean isLoaded() {
        return childConfig != null;
    }

    public UnmodifiableConfig getSpec() {
        return this.config;
    }

    public UnmodifiableConfig getValues() {
        return this.values;
    }

    public void afterReload() {
        this.resetCaches(getValues().valueMap().values());
    }

    private void resetCaches(final Iterable<Object> configValues) {
        configValues.forEach(value -> {
            if (value instanceof ConfigValue) {
                final ConfigValue<?> configValue = (ConfigValue<?>) value;
                configValue.clearCache();
            } else if (value instanceof Config) {
                final Config innerConfig = (Config) value;
                this.resetCaches(innerConfig.valueMap().values());
            }
        });
    }

    public void save()
    {
        Preconditions.checkNotNull(childConfig, "Cannot save config value without assigned Config object present");
        if (childConfig instanceof FileConfig) {
            ((FileConfig)childConfig).save();
        }
    }

    public synchronized boolean isCorrect(CommentedConfig config) {
        LinkedList<String> parentPath = new LinkedList<>();
        return correct(this.config, config, parentPath, Collections.unmodifiableList( parentPath ), (a, b, c, d) -> {}, null, true) == 0;
    }

    public int correct(CommentedConfig config) {
        return correct(config, (action, path, incorrectValue, correctedValue) -> {}, null);
    }

    public synchronized int correct(CommentedConfig config, CorrectionListener listener) {
        return correct(config, listener, null);
    }

    public synchronized int correct(CommentedConfig config, CorrectionListener listener, CorrectionListener commentListener) {
        LinkedList<String> parentPath = new LinkedList<>(); //Linked list for fast add/removes
        int ret = -1;
        try {
            isCorrecting = true;
            ret = correct(this.config, config, parentPath, Collections.unmodifiableList(parentPath), listener, commentListener, false);
        } finally {
            isCorrecting = false;
        }
        return ret;
    }

    private int correct(UnmodifiableConfig spec, CommentedConfig config, LinkedList<String> parentPath, List<String> parentPathUnmodifiable, CorrectionListener listener, CorrectionListener commentListener, boolean dryRun)
    {
        int count = 0;

        Map<String, Object> specMap = spec.valueMap();
        Map<String, Object> configMap = config.valueMap();

        for (Map.Entry<String, Object> specEntry : specMap.entrySet())
        {
            final String key = specEntry.getKey();
            final Object specValue = specEntry.getValue();
            final Object configValue = configMap.get(key);
            final CorrectionAction action = configValue == null ? ADD : REPLACE;

            parentPath.addLast(key);

            if (specValue instanceof Config)
            {
                if (configValue instanceof CommentedConfig)
                {
                    count += correct((Config)specValue, (CommentedConfig)configValue, parentPath, parentPathUnmodifiable, listener, commentListener, dryRun);
                    if (count > 0 && dryRun)
                        return count;
                }
                else if (dryRun)
                {
                    return 1;
                }
                else
                {
                    CommentedConfig newValue = config.createSubConfig();
                    configMap.put(key, newValue);
                    listener.onCorrect(action, parentPathUnmodifiable, configValue, newValue);
                    count++;
                    count += correct((Config)specValue, newValue, parentPath, parentPathUnmodifiable, listener, commentListener, dryRun);
                }

                String newComment = levelComments.get(parentPath);
                String oldComment = config.getComment(key);
                if (!stringsMatchIgnoringNewlines(oldComment, newComment))
                {
                    if(commentListener != null)
                        commentListener.onCorrect(action, parentPathUnmodifiable, oldComment, newComment);

                    if (dryRun)
                        return 1;

                    config.setComment(key, newComment);
                }
            }
            else
            {
                ValueSpec valueSpec = (ValueSpec)specValue;
                if (!valueSpec.test(configValue))
                {
                    if (dryRun)
                        return 1;

                    Object newValue = valueSpec.correct(configValue);
                    configMap.put(key, newValue);
                    listener.onCorrect(action, parentPathUnmodifiable, configValue, newValue);
                    count++;
                }
                String oldComment = config.getComment(key);
                if (!stringsMatchIgnoringNewlines(oldComment, valueSpec.getComment()))
                {
                    if (commentListener != null)
                        commentListener.onCorrect(action, parentPathUnmodifiable, oldComment, valueSpec.getComment());

                    if (dryRun)
                        return 1;

                    config.setComment(key, valueSpec.getComment());
                }
            }

            parentPath.removeLast();
        }

        // Second step: removes the unspecified values
        for (Iterator<Map.Entry<String, Object>> ittr = configMap.entrySet().iterator(); ittr.hasNext();)
        {
            Map.Entry<String, Object> entry = ittr.next();
            if (!specMap.containsKey(entry.getKey()))
            {
                if (dryRun)
                    return 1;

                ittr.remove();
                parentPath.addLast(entry.getKey());
                listener.onCorrect(REMOVE, parentPathUnmodifiable, entry.getValue(), null);
                parentPath.removeLast();
                count++;
            }
        }
        return count;
    }

    private boolean stringsMatchIgnoringNewlines(@Nullable Object obj1, @Nullable Object obj2)
    {
        if(obj1 instanceof String && obj2 instanceof String)
        {
            String string1 = (String) obj1;
            String string2 = (String) obj2;

            if(string1.length() > 0 && string2.length() > 0)
            {
                return string1.replaceAll("\r\n", "\n")
                        .equals(string2.replaceAll("\r\n", "\n"));

            }
        }
        // Fallback for when we're not given Strings, or one of them is empty
        return Objects.equals(obj1, obj2);
    }

    public boolean isVisibleOnModConfigScreen()
    {
        return visibleOnModConfigScreen;
    }

    public static class Builder
    {
        private final Config storage = Config.of(LinkedHashMap::new, InMemoryFormat.withUniversalSupport()); // Use LinkedHashMap for consistent ordering
        private BuilderContext context = new BuilderContext();
        private final Map<List<String>, String> levelComments = new HashMap<>();
        private final Map<List<String>, String> levelTranslationKeys = new HashMap<>();
        private final List<String> currentPath = new ArrayList<>();
        private final List<ConfigValue<?>> values = new ArrayList<>();
        private boolean hasInvalidComment = false;
        private Supplier<ConfigGuiWidgetFactory> widgetFactorySupplier;
        private boolean visibleOnModConfigScreen = true;

        //Object
        public <T> ConfigValue<T> define(String path, T defaultValue) {
            return define(split(path), defaultValue);
        }
        public <T> ConfigValue<T> define(List<String> path, T defaultValue) {
            return define(path, defaultValue, o -> o != null && defaultValue.getClass().isAssignableFrom(o.getClass()));
        }
        public <T> ConfigValue<T> define(String path, T defaultValue, Predicate<Object> validator) {
            return define(split(path), defaultValue, validator);
        }
        public <T> ConfigValue<T> define(List<String> path, T defaultValue, Predicate<Object> validator) {
            Objects.requireNonNull(defaultValue, "Default value can not be null");
            return define(path, () -> defaultValue, validator);
        }
        public <T> ConfigValue<T> define(String path, Supplier<T> defaultSupplier, Predicate<Object> validator) {
            return define(split(path), defaultSupplier, validator);
        }
        public <T> ConfigValue<T> define(List<String> path, Supplier<T> defaultSupplier, Predicate<Object> validator) {
            return define(path, defaultSupplier, validator, Object.class);
        }
        public <T> ConfigValue<T> define(List<String> path, Supplier<T> defaultSupplier, Predicate<Object> validator, Class<?> clazz) {
            context.setClazz(clazz);
            return define(path, new ValueSpec(defaultSupplier, validator, context), defaultSupplier);
        }
        public <T> ConfigValue<T> define(List<String> path, ValueSpec value, Supplier<T> defaultSupplier) { // This is the root where everything at the end of the day ends up.
            if (!currentPath.isEmpty()) {
                List<String> tmp = new ArrayList<>(currentPath.size() + path.size());
                tmp.addAll(currentPath);
                tmp.addAll(path);
                path = tmp;
            }
            storage.set(path, value);
            checkComment(path);
            context = new BuilderContext();

            final ConfigValue<T> result = new ConfigValue<>(this, path, defaultSupplier, widgetFactorySupplier);

            this.widgetFactorySupplier = null;
            return result;
        }
        public <V extends Comparable<? super V>> ConfigValue<V> defineInRange(String path, V defaultValue, V min, V max, Class<V> clazz) {
            return defineInRange(split(path), defaultValue, min, max, clazz);
        }
        public <V extends Comparable<? super V>> ConfigValue<V> defineInRange(List<String> path,  V defaultValue, V min, V max, Class<V> clazz) {
            return defineInRange(path, (Supplier<V>)() -> defaultValue, min, max, clazz);
        }
        public <V extends Comparable<? super V>> ConfigValue<V> defineInRange(String path, Supplier<V> defaultSupplier, V min, V max, Class<V> clazz) {
            return defineInRange(split(path), defaultSupplier, min, max, clazz);
        }
        public <V extends Comparable<? super V>> ConfigValue<V> defineInRange(List<String> path, Supplier<V> defaultSupplier, V min, V max, Class<V> clazz) {
            Range<V> range = new Range<>(clazz, min, max);
            context.setRange(range);
            context.setComment(ObjectArrays.concat(context.getComment(), "Range: " + range.toString()));
            if (context.getErrorDescriber() == null) {
                context.setErrorDescriber(range::getErrorMessage);
            }
            if (min.compareTo(max) > 0)
                throw new IllegalArgumentException("Range min most be less then max.");
            return define(path, defaultSupplier, range);
        }
        public <T> ConfigValue<T> defineInList(String path, T defaultValue, Collection<? extends T> acceptableValues) {
            return defineInList(split(path), defaultValue, acceptableValues);
        }
        public <T> ConfigValue<T> defineInList(String path, Supplier<T> defaultSupplier, Collection<? extends T> acceptableValues) {
            return defineInList(split(path), defaultSupplier, acceptableValues);
        }
        public <T> ConfigValue<T> defineInList(List<String> path, T defaultValue, Collection<? extends T> acceptableValues) {
            return defineInList(path, () -> defaultValue, acceptableValues);
        }
        public <T> ConfigValue<T> defineInList(List<String> path, Supplier<T> defaultSupplier, Collection<? extends T> acceptableValues) {
            return define(path, defaultSupplier, acceptableValues::contains);
        }
        public <T> ConfigValue<List<? extends T>> defineList(String path, List<? extends T> defaultValue, Predicate<Object> elementValidator) {
            return defineList(split(path), defaultValue, elementValidator);
        }
        public <T> ConfigValue<List<? extends T>> defineList(String path, Supplier<List<? extends T>> defaultSupplier, Predicate<Object> elementValidator) {
            return defineList(split(path), defaultSupplier, elementValidator);
        }
        public <T> ConfigValue<List<? extends T>> defineList(List<String> path, List<? extends T> defaultValue, Predicate<Object> elementValidator) {
            return defineList(path, () -> defaultValue, elementValidator);
        }
        public <T> ConfigValue<List<? extends T>> defineList(List<String> path, Supplier<List<? extends T>> defaultSupplier, Predicate<Object> elementValidator) {
            context.setClazz(List.class);
            return define(path, new ValueSpec(defaultSupplier, x -> x instanceof List && ((List<?>) x).stream().allMatch( elementValidator ), context) {
                @Override
                public Object correct(Object value) {
                    if (value == null || !(value instanceof List) || ((List<?>)value).isEmpty()) {
                        // Forge Config API Port: replace with SLF4J logger
                        LogUtils.getLogger().debug(CORE, "List on key {} is deemed to need correction. It is null, not a list, or an empty list. Modders, consider defineListAllowEmpty?", path.get(path.size() - 1));
                        return getDefault();
                    }
                    List<?> list = Lists.newArrayList((List<?>) value);
                    list.removeIf(elementValidator.negate());
                    if (list.isEmpty()) {
                        // Forge Config API Port: replace with SLF4J logger
                        LogUtils.getLogger().debug(CORE, "List on key {} is deemed to need correction. It failed validation.", path.get(path.size() - 1));
                        return getDefault();
                    }
                    return list;
                }
            }, defaultSupplier);
        }

        public <T> ConfigValue<List<? extends T>> defineListAllowEmpty(List<String> path, Supplier<List<? extends T>> defaultSupplier, Predicate<Object> elementValidator) {
            context.setClazz(List.class);
            return define(path, new ValueSpec(defaultSupplier, x -> x instanceof List && ((List<?>) x).stream().allMatch( elementValidator ), context) {
                @Override
                public Object correct(Object value) {
                    if (value == null || !(value instanceof List)) {
                        // Forge Config API Port: replace with SLF4J logger
                        LogUtils.getLogger().debug(CORE, "List on key {} is deemed to need correction, as it is null or not a list.", path.get(path.size() - 1));
                        return getDefault();
                    }
                    List<?> list = Lists.newArrayList((List<?>) value);
                    list.removeIf(elementValidator.negate());
                    if (list.isEmpty()) {
                        // Forge Config API Port: replace with SLF4J logger
                        LogUtils.getLogger().debug(CORE, "List on key {} is deemed to need correction. It failed validation.", path.get(path.size() - 1));
                        return getDefault();
                    }
                    return list;
                }
            }, defaultSupplier);
        }

        //Enum
        public <V extends Enum<V>> EnumValue<V> defineEnum(String path, V defaultValue) {
            return defineEnum(split(path), defaultValue);
        }
        public <V extends Enum<V>> EnumValue<V> defineEnum(String path, V defaultValue, EnumGetMethod converter) {
            return defineEnum(split(path), defaultValue, converter);
        }
        public <V extends Enum<V>> EnumValue<V> defineEnum(List<String> path, V defaultValue) {
            return defineEnum(path, defaultValue, defaultValue.getDeclaringClass().getEnumConstants());
        }
        public <V extends Enum<V>> EnumValue<V> defineEnum(List<String> path, V defaultValue, EnumGetMethod converter) {
            return defineEnum(path, defaultValue, converter, defaultValue.getDeclaringClass().getEnumConstants());
        }
        @SuppressWarnings("unchecked")
        public <V extends Enum<V>> EnumValue<V> defineEnum(String path, V defaultValue, V... acceptableValues) {
            return defineEnum(split(path), defaultValue, acceptableValues);
        }
        @SuppressWarnings("unchecked")
        public <V extends Enum<V>> EnumValue<V> defineEnum(String path, V defaultValue, EnumGetMethod converter, V... acceptableValues) {
            return defineEnum(split(path), defaultValue, converter, acceptableValues);
        }
        @SuppressWarnings("unchecked")
        public <V extends Enum<V>> EnumValue<V> defineEnum(List<String> path, V defaultValue, V... acceptableValues) {
            return defineEnum(path, defaultValue, (Collection<V>) Arrays.asList(acceptableValues));
        }
        @SuppressWarnings("unchecked")
        public <V extends Enum<V>> EnumValue<V> defineEnum(List<String> path, V defaultValue, EnumGetMethod converter, V... acceptableValues) {
            return defineEnum(path, defaultValue, converter, Arrays.asList(acceptableValues));
        }
        public <V extends Enum<V>> EnumValue<V> defineEnum(String path, V defaultValue, Collection<V> acceptableValues) {
            return defineEnum(split(path), defaultValue, acceptableValues);
        }
        public <V extends Enum<V>> EnumValue<V> defineEnum(String path, V defaultValue, EnumGetMethod converter, Collection<V> acceptableValues) {
            return defineEnum(split(path), defaultValue, converter, acceptableValues);
        }
        public <V extends Enum<V>> EnumValue<V> defineEnum(List<String> path, V defaultValue, Collection<V> acceptableValues) {
            return defineEnum(path, defaultValue, EnumGetMethod.NAME_IGNORECASE, acceptableValues);
        }
        public <V extends Enum<V>> EnumValue<V> defineEnum(List<String> path, V defaultValue, EnumGetMethod converter, Collection<V> acceptableValues) {
            return defineEnum(path, defaultValue, converter, obj -> {
                if (obj instanceof Enum) {
                    return acceptableValues.contains(obj);
                }
                if (obj == null) {
                    return false;
                }
                try {
                    return acceptableValues.contains(converter.get(obj, defaultValue.getDeclaringClass()));
                } catch (IllegalArgumentException | ClassCastException e) {
                    return false;
                }
            });
        }
        public <V extends Enum<V>> EnumValue<V> defineEnum(String path, V defaultValue, Predicate<Object> validator) {
            return defineEnum(split(path), defaultValue, validator);
        }
        public <V extends Enum<V>> EnumValue<V> defineEnum(String path, V defaultValue, EnumGetMethod converter, Predicate<Object> validator) {
            return defineEnum(split(path), defaultValue, converter, validator);
        }
        public <V extends Enum<V>> EnumValue<V> defineEnum(List<String> path, V defaultValue, Predicate<Object> validator) {
            return defineEnum(path, () -> defaultValue, validator, defaultValue.getDeclaringClass());
        }
        public <V extends Enum<V>> EnumValue<V> defineEnum(List<String> path, V defaultValue, EnumGetMethod converter, Predicate<Object> validator) {
            return defineEnum(path, () -> defaultValue, converter, validator, defaultValue.getDeclaringClass());
        }
        public <V extends Enum<V>> EnumValue<V> defineEnum(String path, Supplier<V> defaultSupplier, Predicate<Object> validator, Class<V> clazz) {
            return defineEnum(split(path), defaultSupplier, validator, clazz);
        }
        public <V extends Enum<V>> EnumValue<V> defineEnum(String path, Supplier<V> defaultSupplier, EnumGetMethod converter, Predicate<Object> validator, Class<V> clazz) {
            return defineEnum(split(path), defaultSupplier, converter, validator, clazz);
        }
        public <V extends Enum<V>> EnumValue<V> defineEnum(List<String> path, Supplier<V> defaultSupplier, Predicate<Object> validator, Class<V> clazz) {
            return defineEnum(path, defaultSupplier, EnumGetMethod.NAME_IGNORECASE, validator, clazz);
        }
        public <V extends Enum<V>> EnumValue<V> defineEnum(List<String> path, Supplier<V> defaultSupplier, EnumGetMethod converter, Predicate<Object> validator, Class<V> clazz) {
            context.setClazz(clazz);
            V[] allowedValues = clazz.getEnumConstants();
            if (context.getErrorDescriber() == null) {
                context.setErrorDescriber((obj) -> {
                    if (obj instanceof String string) {
                        return Component.translatable("forge.configgui.error.enum.invalidName", string, Arrays.stream(allowedValues).filter(validator).map(Enum::name).collect(Collectors.joining("\n - ", "\n - ", "")));
                    }

                    return Component.translatable("forge.configgui.error.enum.needsToBeText");
                });
            }
            context.setComment(ObjectArrays.concat(context.getComment(), "Allowed Values: " + Arrays.stream(allowedValues).filter(validator).map(Enum::name).collect(Collectors.joining(", "))));
            final EnumValue<V> result = new EnumValue<V>(this, define(path, new ValueSpec(defaultSupplier, validator, context), defaultSupplier).getPath(), defaultSupplier, converter, clazz, this.widgetFactorySupplier);

            this.widgetFactorySupplier = null;
            return result;
        }

        //boolean
        public BooleanValue define(String path, boolean defaultValue) {
            return define(split(path), defaultValue);
        }
        public BooleanValue define(List<String> path, boolean defaultValue) {
            return define(path, (Supplier<Boolean>)() -> defaultValue);
        }
        public BooleanValue define(String path, Supplier<Boolean> defaultSupplier) {
            return define(split(path), defaultSupplier);
        }
        public BooleanValue define(List<String> path, Supplier<Boolean> defaultSupplier) {
            if (context.getErrorDescriber() == null) {
                context.setErrorDescriber((obj) -> {
                    if (obj instanceof String string) {
                        return Component.translatable("forge.configgui.error.boolean.notTrueOrFalse", string);
                    }

                    return Component.translatable("forge.configgui.error.boolean.needsToBeText");
                });
            }
            final BooleanValue result = new BooleanValue(this, define(path, defaultSupplier, o -> {
                if (o instanceof String) return ((String)o).equalsIgnoreCase("true") || ((String)o).equalsIgnoreCase("false");
                return o instanceof Boolean;
            }, Boolean.class).getPath(), defaultSupplier, this.widgetFactorySupplier);

            this.widgetFactorySupplier = null;
            return result;
        }

        //Double
        public DoubleValue defineInRange(String path, double defaultValue, double min, double max) {
            return defineInRange(split(path), defaultValue, min, max);
        }
        public DoubleValue defineInRange(List<String> path, double defaultValue, double min, double max) {
            return defineInRange(path, (Supplier<Double>)() -> defaultValue, min, max);
        }
        public DoubleValue defineInRange(String path, Supplier<Double> defaultSupplier, double min, double max) {
            return defineInRange(split(path), defaultSupplier, min, max);
        }
        public DoubleValue defineInRange(List<String> path, Supplier<Double> defaultSupplier, double min, double max) {
            final DoubleValue result = new DoubleValue(this, defineInRange(path, defaultSupplier, min, max, Double.class).getPath(), defaultSupplier, this.widgetFactorySupplier);

            this.widgetFactorySupplier = null;
            return result;
        }

        //Ints
        public IntValue defineInRange(String path, int defaultValue, int min, int max) {
            return defineInRange(split(path), defaultValue, min, max);
        }
        public IntValue defineInRange(List<String> path, int defaultValue, int min, int max) {
            return defineInRange(path, (Supplier<Integer>)() -> defaultValue, min, max);
        }
        public IntValue defineInRange(String path, Supplier<Integer> defaultSupplier, int min, int max) {
            return defineInRange(split(path), defaultSupplier, min, max);
        }
        public IntValue defineInRange(List<String> path, Supplier<Integer> defaultSupplier, int min, int max) {
            final IntValue result = new IntValue(this, defineInRange(path, defaultSupplier, min, max, Integer.class).getPath(), defaultSupplier, this.widgetFactorySupplier);

            this.widgetFactorySupplier = null;
            return result;
        }

        //Longs
        public LongValue defineInRange(String path, long defaultValue, long min, long max) {
            return defineInRange(split(path), defaultValue, min, max);
        }
        public LongValue defineInRange(List<String> path, long defaultValue, long min, long max) {
            return defineInRange(path, (Supplier<Long>)() -> defaultValue, min, max);
        }
        public LongValue defineInRange(String path, Supplier<Long> defaultSupplier, long min, long max) {
            return defineInRange(split(path), defaultSupplier, min, max);
        }
        public LongValue defineInRange(List<String> path, Supplier<Long> defaultSupplier, long min, long max) {
            final LongValue result = new LongValue(this, defineInRange(path, defaultSupplier, min, max, Long.class).getPath(), defaultSupplier, this.widgetFactorySupplier);

            this.widgetFactorySupplier = null;
            return result;
        }

        public Builder comment(String comment)
        {
            hasInvalidComment = comment == null || comment.isEmpty();
            if (hasInvalidComment)
            {
                comment = "No comment";
            }
            context.setComment(comment);
            return this;
        }
        public Builder comment(String... comment)
        {
            hasInvalidComment = comment == null || comment.length < 1 || (comment.length == 1 && comment[0].isEmpty());
            if (hasInvalidComment)
            {
                comment = new String[] {"No comment"};
            }

            context.setComment(comment);
            return this;
        }

        public Builder translation(String translationKey)
        {
            context.setTranslationKey(translationKey);
            return this;
        }

        public Builder worldRestart()
        {
            context.worldRestart();
            return this;
        }

        public Builder withErrorDescriber(Function<Object, Component> errorDescriber) {
            context.setErrorDescriber(errorDescriber);
            return this;
        }

        public Builder push(String path) {
            return push(split(path));
        }

        public Builder push(List<String> path) {
            currentPath.addAll(path);
            checkComment(currentPath);
            if (context.hasComment()) {
                levelComments.put(new ArrayList<String>(currentPath), context.buildComment());
                context.setComment(); // Set to empty
            }
            if (context.getTranslationKey() != null) {
                levelTranslationKeys.put(new ArrayList<String>(currentPath), context.getTranslationKey());
                context.setTranslationKey(null);
            }
            context.ensureEmpty();
            return this;
        }

        public Builder pop() {
            return pop(1);
        }

        public Builder pop(int count) {
            if (count > currentPath.size())
                throw new IllegalArgumentException("Attempted to pop " + count + " elements when we only had: " + currentPath);
            for (int x = 0; x < count; x++)
                currentPath.remove(currentPath.size() - 1);
            return this;
        }

        public <T> Pair<T, ForgeConfigSpec> configure(Function<Builder, T> consumer) {
            T o = consumer.apply(this);
            return Pair.of(o, this.build());
        }

        public ForgeConfigSpec build()
        {
            context.ensureEmpty();
            Config valueCfg = Config.of(Config.getDefaultMapCreator(true, true), InMemoryFormat.withSupport(ConfigValue.class::isAssignableFrom));
            values.forEach(v -> valueCfg.set(v.getPath(), v));

            ForgeConfigSpec ret = new ForgeConfigSpec(storage, valueCfg, levelComments, levelTranslationKeys, visibleOnModConfigScreen);
            values.forEach(v -> v.spec = ret);
            return ret;
        }

        private void checkComment(List<String> path)
        {
            if (hasInvalidComment)
            {
                hasInvalidComment = false;
                if (FabricLoader.getInstance().isDevelopmentEnvironment())
                {
                    // Forge Config API Port: replace with SLF4J logger
                    LogUtils.getLogger().error(CORE, "Null comment for config option {}, this is invalid and may be disallowed in the future.",
                            DOT_JOINER.join(path));
                }
            }
        }

        public Builder useConfigGuiWidgetFactory(final Supplier<ConfigGuiWidgetFactory> widgetFactorySupplier)
        {
            this.widgetFactorySupplier = widgetFactorySupplier;
            return this;
        }

        public Builder removeSpecFromModConfigScreen()
        {
            this.visibleOnModConfigScreen = false;
            return this;
        }

        public interface BuilderConsumer {
            void accept(Builder builder);
        }
    }

    private static class BuilderContext
    {
        private @NotNull String[] comment = new String[0];
        private String langKey;
        private Range<?> range;
        private boolean worldRestart = false;
        private Class<?> clazz;
        private Function<Object, Component> errorDescriber;

        public void setComment(String... value)
        {
            validate(value == null, "Passed in null value for comment");
            this.comment = value;
        }
        public boolean hasComment() { return this.comment.length > 0; }
        public String[] getComment() { return this.comment; }
        public String buildComment() { return LINE_JOINER.join(comment); }
        public void setTranslationKey(String value) { this.langKey = value; }
        public String getTranslationKey() { return this.langKey; }
        public <V extends Comparable<? super V>> void setRange(Range<V> value)
        {
            this.range = value;
            this.setClazz(value.getClazz());
        }
        @SuppressWarnings("unchecked")
        public <V extends Comparable<? super V>> Range<V> getRange() { return (Range<V>)this.range; }
        public void worldRestart() { this.worldRestart = true; }
        public boolean needsWorldRestart() { return this.worldRestart; }
        public void setClazz(Class<?> clazz) { this.clazz = clazz; }
        public Class<?> getClazz(){ return this.clazz; }
        public Function<Object, Component> getErrorDescriber() { return errorDescriber; }
        public void setErrorDescriber(final Function<Object, Component> errorDescriber) { this.errorDescriber = errorDescriber; }

        public void ensureEmpty()
        {
            validate(hasComment(), "Non-empty comment when empty expected");
            validate(langKey, "Non-null translation key when null expected");
            validate(range, "Non-null range when null expected");
            validate(worldRestart, "Dangeling world restart value set to true");
            validate(errorDescriber, "Non-null error describer when null expected");
        }

        private void validate(Object value, String message)
        {
            if (value != null)
                throw new IllegalStateException(message);
        }
        private void validate(boolean value, String message)
        {
            if (value)
                throw new IllegalStateException(message);
        }
    }

    @SuppressWarnings("unused")
    private static class Range<V extends Comparable<? super V>> implements Predicate<Object>
    {
        private final Class<? extends V> clazz;
        private final V min;
        private final V max;

        private Range(Class<V> clazz, V min, V max)
        {
            this.clazz = clazz;
            this.min = min;
            this.max = max;
        }

        public Class<? extends V> getClazz() { return clazz; }
        public V getMin() { return min; }
        public V getMax() { return max; }

        private boolean isNumber(Object other)
        {
            return Number.class.isAssignableFrom(clazz) && other instanceof Number;
        }

        @Override
        public boolean test(Object t)
        {
            if (isNumber(t))
            {
                Number n = (Number) t;
                boolean result = ((Number)min).doubleValue() <= n.doubleValue() && n.doubleValue() <= ((Number)max).doubleValue();
                if (!result)
                {
                    // Forge Config API Port: replace with SLF4J logger
                    LogUtils.getLogger().debug(CORE, "Range value {} is not within its bounds {}-{}", n.doubleValue(), ((Number)min).doubleValue(), ((Number)max).doubleValue());
                }
                return result;
            }
            if (!clazz.isInstance(t)) return false;
            V c = clazz.cast(t);

            boolean result = c.compareTo(min) >= 0 && c.compareTo(max) <= 0;
            if (!result)
            {
                // Forge Config API Port: replace with SLF4J logger
                LogUtils.getLogger().debug(CORE, "Range value {} is not within its bounds {}-{}", c, min, max);
            }
            return result;
        }

        public Component getErrorMessage(Object t) {
            if (isNumber(t))
            {
                Number n = (Number) t;
                boolean result = ((Number)min).doubleValue() <= n.doubleValue() && n.doubleValue() <= ((Number)max).doubleValue();
                if (!result)
                {
                    return Component.translatable("forge.configgui.error.ranged.notInBounds", n, min, max);
                }

                throw new IllegalStateException("Called the error message producor for a valid value!");
            }
            if (!clazz.isInstance(t)) return Component.translatable("forge.configgui.error.ranged.needsToBeOfType", clazz.getSimpleName());
            V c = clazz.cast(t);

            boolean result = c.compareTo(min) >= 0 && c.compareTo(max) <= 0;
            if (!result)
            {
                return Component.translatable("forge.configgui.error.ranged.notInBounds", c, min, max);
            }

            throw new IllegalStateException("Called the error message producor for a valid value!");
        }

        public Object correct(Object value, Object def)
        {
            if (isNumber(value))
            {
                Number n = (Number) value;
                return n.doubleValue() < ((Number)min).doubleValue() ? min : n.doubleValue() > ((Number)max).doubleValue() ? max : value;
            }
            if (!clazz.isInstance(value)) return def;
            V c = clazz.cast(value);
            return c.compareTo(min) < 0 ? min : c.compareTo(max) > 0 ? max : value;
        }

        @Override
        public String toString()
        {
            if (clazz == Integer.class) {
                if (max.equals(Integer.MAX_VALUE)) {
                    return "> " + min;
                } else if (min.equals(Integer.MIN_VALUE)) {
                    return "< " + max;
                }
            } // TODO add more special cases?
            return min + " ~ " + max;
        }
    }

    public static class ValueSpec
    {
        private final String comment;
        private final String langKey;
        private final Range<?> range;
        private final boolean worldRestart;
        private final Class<?> clazz;
        private final Supplier<?> supplier;
        private final Predicate<Object> validator;
        private final Function<Object, Component> errorDescriber;
        private Object _default = null;

        private ValueSpec(Supplier<?> supplier, Predicate<Object> validator, BuilderContext context)
        {
            Objects.requireNonNull(supplier, "Default supplier can not be null");
            Objects.requireNonNull(validator, "Validator can not be null");

            this.comment = context.hasComment() ? context.buildComment() : null;
            this.langKey = context.getTranslationKey();
            this.range = context.getRange();
            this.worldRestart = context.needsWorldRestart();
            this.clazz = context.getClazz();
            this.supplier = supplier;
            this.validator = validator;
            this.errorDescriber = context.getErrorDescriber();
        }

        public String getComment() { return comment; }
        public String getTranslationKey() { return langKey; }
        @SuppressWarnings("unchecked")
        public <V extends Comparable<? super V>> Range<V> getRange() { return (Range<V>)this.range; }
        public boolean needsWorldRestart() { return this.worldRestart; }
        public Class<?> getClazz(){ return this.clazz; }
        public boolean test(Object value) { return validator.test(value); }
        public Object correct(Object value) { return range == null ? getDefault() : range.correct(value, getDefault()); }
        public Component getError(Object value)
        {
            return this.errorDescriber != null ? this.errorDescriber.apply(value) : Component.translatable("forge.configgui.entryInvalid");
        }
        public Object getDefault()
        {
            if (_default == null)
                _default = supplier.get();
            return _default;
        }
    }

    public static class ConfigValue<T> implements Supplier<T>
    {
        private static final boolean USE_CACHES = true;

        private final Builder parent;
        private final List<String> path;
        private final Supplier<T> defaultSupplier;
        private Supplier<ConfigGuiWidgetFactory> screenWidgetFactorySupplier;

        private T cachedValue = null;

        private ForgeConfigSpec spec;

        ConfigValue(Builder parent, List<String> path, Supplier<T> defaultSupplier, final @Nullable Supplier<ConfigGuiWidgetFactory> widgetFactorySupplier)
        {
            this.parent = parent;
            this.path = path;
            this.defaultSupplier = defaultSupplier;
            this.screenWidgetFactorySupplier = widgetFactorySupplier;
            this.parent.values.add(this);
        }

        public List<String> getPath()
        {
            return Lists.newArrayList(path);
        }

        /**
         * Returns the actual value for the configuration setting, throwing if the config has not yet been loaded.
         *
         * @return the actual value for the setting
         * @throws NullPointerException if the {@link ForgeConfigSpec config spec} object that will contain this has
         *                              not yet been built
         * @throws IllegalStateException if the associated config has not yet been loaded
         */
        @Override
        public T get()
        {
            Preconditions.checkNotNull(spec, "Cannot get config value before spec is built");
            // TODO: Remove this dev-time check so this errors out on both production and dev
            // This is dev-time-only in 1.19.x, to avoid breaking already published mods while forcing devs to fix their errors
            if (FabricLoader.getInstance().isDevelopmentEnvironment())
            {
                // When the above if-check is removed, change message to "Cannot get config value before config is loaded"
                Preconditions.checkState(spec.childConfig != null, """
                        Cannot get config value before config is loaded.
                        This error is currently only thrown in the development environment, to avoid breaking published mods.
                        In a future version, this will also throw in the production environment.
                        """);
            }

            if (spec.childConfig == null)
                return defaultSupplier.get();

            if (USE_CACHES && cachedValue == null)
                cachedValue = getRaw(spec.childConfig, path, defaultSupplier);
            else if (!USE_CACHES)
                return getRaw(spec.childConfig, path, defaultSupplier);

            return cachedValue;
        }

        protected T getRaw(Config config, List<String> path, Supplier<T> defaultSupplier)
        {
            return config.getOrElse(path, defaultSupplier);
        }

        /**
         * {@return the default value for the configuration setting}
         */
        public T getDefault()
        {
            return defaultSupplier.get();
        }

        public Builder next()
        {
            return parent;
        }

        public void save()
        {
            Preconditions.checkNotNull(spec, "Cannot save config value before spec is built");
            Preconditions.checkNotNull(spec.childConfig, "Cannot save config value without assigned Config object present");
            spec.save();
        }

        public void set(T value)
        {
            Preconditions.checkNotNull(spec, "Cannot set config value before spec is built");
            Preconditions.checkNotNull(spec.childConfig, "Cannot set config value without assigned Config object present");
            spec.childConfig.set(path, value);
            this.cachedValue = value;
        }

        public void clearCache() {
            this.cachedValue = null;
        }

        public Supplier<ConfigGuiWidgetFactory> getScreenWidgetFactorySupplier() {
            return this.screenWidgetFactorySupplier;
        }

        void setScreenWidgetFactorySupplier(final Supplier<ConfigGuiWidgetFactory> screenWidgetFactorySupplier)
        {
            this.screenWidgetFactorySupplier = screenWidgetFactorySupplier;
        }
    }

    public static class BooleanValue extends ConfigValue<Boolean>
    {
        private static final Supplier<ConfigGuiWidgetFactory> FALLBACK_FACTORY = () -> ConfigGuiWidget.BooleanWidget.FACTORY;

        BooleanValue(Builder parent, List<String> path, Supplier<Boolean> defaultSupplier, @Nullable final Supplier<ConfigGuiWidgetFactory> widgetFactorySupplier)
        {
            super(parent, path, defaultSupplier, widgetFactorySupplier);
            this.setScreenWidgetFactorySupplier(FALLBACK_FACTORY);
        }
    }

    public static class IntValue extends ConfigValue<Integer>
    {
        private static final Supplier<ConfigGuiWidgetFactory> FALLBACK_FACTORY = () -> ConfigGuiWidget.NumberWidget.INTEGER;

        IntValue(Builder parent, List<String> path, Supplier<Integer> defaultSupplier, @Nullable final Supplier<ConfigGuiWidgetFactory> widgetFactorySupplier)
        {
            super(parent, path, defaultSupplier, widgetFactorySupplier);
            this.setScreenWidgetFactorySupplier(FALLBACK_FACTORY);
        }

        @Override
        protected Integer getRaw(Config config, List<String> path, Supplier<Integer> defaultSupplier)
        {
            return config.getIntOrElse(path, defaultSupplier::get);
        }
    }

    public static class LongValue extends ConfigValue<Long>
    {
        private static final Supplier<ConfigGuiWidgetFactory> FALLBACK_FACTORY = () -> ConfigGuiWidget.NumberWidget.LONG;

        LongValue(Builder parent, List<String> path, Supplier<Long> defaultSupplier, @Nullable final Supplier<ConfigGuiWidgetFactory> widgetFactorySupplier)
        {
            super(parent, path, defaultSupplier, widgetFactorySupplier);
            this.setScreenWidgetFactorySupplier(FALLBACK_FACTORY);
        }

        @Override
        protected Long getRaw(Config config, List<String> path, Supplier<Long> defaultSupplier)
        {
            return config.getLongOrElse(path, defaultSupplier::get);
        }
    }

    public static class DoubleValue extends ConfigValue<Double>
    {
        private static final Supplier<ConfigGuiWidgetFactory> FALLBACK_FACTORY = () -> ConfigGuiWidget.NumberWidget.DOUBLE;

        DoubleValue(Builder parent, List<String> path, Supplier<Double> defaultSupplier, @Nullable final Supplier<ConfigGuiWidgetFactory> widgetFactorySupplier)
        {
            super(parent, path, defaultSupplier, widgetFactorySupplier);
            this.setScreenWidgetFactorySupplier(FALLBACK_FACTORY);
        }

        @Override
        protected Double getRaw(Config config, List<String> path, Supplier<Double> defaultSupplier)
        {
            Number n = config.<Number>get(path);
            return n == null ? defaultSupplier.get() : n.doubleValue();
        }
    }

    public static class EnumValue<T extends Enum<T>> extends ConfigValue<T>
    {
        private static final Supplier<ConfigGuiWidgetFactory> FALLBACK_FACTORY = () -> ConfigGuiWidget.EnumWidget.FACTORY;

        private final EnumGetMethod converter;
        private final Class<T> clazz;

        EnumValue(Builder parent, List<String> path, Supplier<T> defaultSupplier, EnumGetMethod converter, Class<T> clazz, @Nullable final Supplier<ConfigGuiWidgetFactory> widgetFactorySupplier)
        {
            super(parent, path, defaultSupplier, widgetFactorySupplier);
            this.setScreenWidgetFactorySupplier(FALLBACK_FACTORY);
            this.converter = converter;
            this.clazz = clazz;
        }

        @Override
        protected T getRaw(Config config, List<String> path, Supplier<T> defaultSupplier)
        {
            return config.getEnumOrElse(path, clazz, converter, defaultSupplier);
        }

        public Class<T> getEnumClass()
        {
            return clazz;
        }
    }

    private static final Joiner LINE_JOINER = Joiner.on("\n");
    private static final Joiner DOT_JOINER = Joiner.on(".");
    private static final Splitter DOT_SPLITTER = Splitter.on(".");
    private static List<String> split(String path)
    {
        return Lists.newArrayList(DOT_SPLITTER.split(path));
    }
}