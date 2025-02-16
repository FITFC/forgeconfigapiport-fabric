/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.client.gui.config.widgets;

import com.electronwill.nightconfig.core.AbstractConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.client.gui.widget.ImageContentButton;
import net.minecraftforge.common.ForgeConfigSpec;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

public class ConfigGuiScreen extends Screen
{
    private static final ResourceLocation FORGE_EXPERIMENTAL_WARNING_ICON = new ResourceLocation("forge", "textures/gui/experimental_warning.png");
    private static final ResourceLocation FORGE_NEEDS_WORLD_RELOAD_ICON = new ResourceLocation("forge", "textures/gui/needs_world_reload.png");
    private static final ResourceLocation FORGE_RESET_TO_INITIAL_ICON = new ResourceLocation("forge", "textures/gui/reset_to_initial.png");
    private static final ResourceLocation FORGE_RESET_TO_DEFAULT_ICON = new ResourceLocation("forge", "textures/gui/reset_to_default.png");
    private static final ResourceLocation FORGE_SEARCH_ICON = new ResourceLocation("forge", "textures/gui/search.png");
    private final Map<List<String>, Object> currentValues = Maps.newHashMap();
    private final Map<List<String>, Object> initialValues = Maps.newHashMap();
    private final Collection<SpecificationData> specs;
    private final Set<ConfigEntry> invalidEntries = Sets.newHashSet();
    private final Runnable onClose;
    private ConfigGuiScreen.ConfigEntryList configEntryList;
    private Button doneButton;
    private ImageContentButton resetToInitialButton = null;
    private ImageContentButton resetToDefaultButton = null;
    @Nullable
    private List<FormattedCharSequence> tooltip;

    public ConfigGuiScreen(Component title, final Collection<SpecificationData> specs, Runnable onClose)
    {
        super(title);
        this.specs = specs;
        this.onClose = onClose;
    }

    @NotNull
    private static Comparator<List<String>> createListComparator()
    {
        return (o1, o2) ->
        {
            if (o1.size() == o2.size())
            {
                for (int i = 0; i < o1.size(); i++)
                {
                    final int result = o1.get(i).compareTo(o2.get(i));
                    if (result != 0)
                    {
                        return result;
                    }
                }
                return 0;
            }

            return o1.size() - o2.size();
        };
    }

    @Override
    public void onClose()
    {
        super.onClose();
        this.onClose.run();
    }

    @Override
    public boolean keyPressed(final int key, final int scanCode, final int modifiers)
    {
        if (key == GLFW_KEY_ESCAPE && this.shouldCloseOnEsc())
        {
            this.configEntryList.onCancel(this::onClose);
            return true;
        }

        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    protected void init()
    {
        super.init();
        Objects.requireNonNull(this.minecraft).keyboardHandler.setSendRepeatsToGui(true);
        final EditBox searchBox = this.addRenderableWidget(new EditBox(this.font, this.width / 2 + 32, 16,
                (this.width - 100) / 2 + 5, 20, Component.translatable("forge.configgui.search")));
        searchBox.setResponder(searchString -> this.configEntryList.initializeEntries(searchString.trim()));
        this.configEntryList = addWidget(new ConfigEntryList());
        this.doneButton = this.addRenderableWidget(new Button(this.width / 2 - 155, this.height - 29, 150, 20,
                CommonComponents.GUI_DONE, (button) -> {
            this.configEntryList.onSave();
            this.onClose();
        }));
        this.addRenderableWidget(new Button(this.width / 2 - 155 + 160, this.height - 29, 150, 20,
                CommonComponents.GUI_CANCEL, (button) -> this.configEntryList.onCancel(this::onClose)));
        this.resetToInitialButton = this.addRenderableWidget(new ImageContentButton(this.width / 2 - 155 + 320,
                this.height - 29, 24, 20, 0, 2, 0, FORGE_RESET_TO_INITIAL_ICON, 24, 24,
                botton -> this.configEntryList.resetToInitial(), Component.translatable("forge.configgui.resetAllToInitial")));
        this.resetToDefaultButton = this.addRenderableWidget(new ImageContentButton(this.width / 2 - 155 + 346,
                this.height - 29, 24, 20, 0, 2, 0, FORGE_RESET_TO_DEFAULT_ICON, 24, 24,
                botton -> this.configEntryList.resetToDefault(), Component.translatable("forge.configgui.resetAllToDefault")));
    }

    @Override
    public void removed()
    {
        Objects.requireNonNull(this.minecraft).keyboardHandler.setSendRepeatsToGui(false);
    }

    @Override
    public void render(@NotNull PoseStack poseStack, int mouseX, int mouseY, float partialTick)
    {
        if (Minecraft.getInstance().screen != this) {
            return;
        }

        this.tooltip = null;
        this.configEntryList.render(poseStack, mouseX, mouseY, partialTick);
        drawString(poseStack, this.font, this.title, 25, 20, 16777215);
        super.render(poseStack, mouseX, mouseY, partialTick);
        if (this.tooltip != null)
        {
            this.renderTooltip(poseStack, this.tooltip, mouseX, mouseY);
        }
        renderSearchIcon(poseStack);
        this.resetToInitialButton.setActive(this.configEntryList.hasEntriesWhichCanBeResetToInitial(Collections.emptyList()));
        this.resetToDefaultButton.setActive(this.configEntryList.hasEntriesWhichCanBeResetToDefault(Collections.emptyList()));
    }

    private void renderSearchIcon(PoseStack stack)
    {
        RenderSystem.setShaderTexture(0, FORGE_SEARCH_ICON);
        GuiComponent.blit(stack, this.width / 2 + 12, 20, 0.0F, 0.0F, 14, 14, 14, 14);
    }

    void setTooltip(@Nullable List<FormattedCharSequence> lines)
    {
        this.tooltip = lines;
    }

    private void updateDoneButton()
    {
        this.doneButton.active = this.invalidEntries.isEmpty();
    }

    void markInvalid(ConfigEntry entry)
    {
        this.invalidEntries.add(entry);
        this.updateDoneButton();
    }

    void clearInvalid(ConfigEntry entry)
    {
        this.invalidEntries.remove(entry);
        this.updateDoneButton();
    }

    @Override
    public boolean shouldCloseOnEsc()
    {
        return this.invalidEntries.isEmpty();
    }

    public static abstract class AbstractListEntry extends ContainerObjectSelectionList.Entry<AbstractListEntry>
    {
        @Nullable
        protected List<FormattedCharSequence> tooltip = null;

        protected AbstractListEntry()
        {
        }

        @Override
        public abstract void render(@NotNull PoseStack poseStack, int entryIdx, int top, int left, int entryWidth,
                                    int entryHeight, int mouseX, int mouseY, boolean isHovered, float partialTick);

        @Override
        public abstract @NotNull List<? extends NarratableEntry> narratables();

        @Override
        public abstract @NotNull List<? extends GuiEventListener> children();

        @Nullable
        public List<FormattedCharSequence> getTooltip()
        {
            return tooltip;
        }
    }

    public class ConfigEntryList extends ContainerObjectSelectionList<AbstractListEntry>
    {

        public ConfigEntryList()
        {
            super(Objects.requireNonNull(ConfigGuiScreen.this.minecraft), ConfigGuiScreen.this.width,
                    ConfigGuiScreen.this.height, 43, ConfigGuiScreen.this.height - 32, 24);
            initializeEntries("");
        }


        @Override
        public int getRowWidth()
        {
            return ConfigGuiScreen.this.width - 50;
        }

        @Override
        public void render(@NotNull PoseStack poseStack, int mouseX, int mouseY, float partialTickTime)
        {
            setTooltip(null);
            super.render(poseStack, mouseX, mouseY, partialTickTime);
        }

        @Override
        public int getScrollbarPosition()
        {
            return this.getRowLeft() + this.getRowWidth();
        }


        private void initializeEntries(String searchString)
        {
            this.clearEntries();
            final Map<List<String>, ConfigEntry> map = Maps.newHashMap();

            specs.forEach(spec -> initializeConfigSpecification(searchString, map, spec));
        }

        private void initializeConfigSpecification(final String searchString,
                                                   final Map<List<String>, ConfigEntry> map,
                                                   final SpecificationData spec)
        {
            if (!spec.configSpec().isLoaded()) return;

            spec.configSpec()
                    .getValues()
                    .valueMap()
                    .values()
                    .forEach(configValue -> handleConfigValue(spec, map, configValue));

            final Set<List<String>> groups = new HashSet<>();
            map.entrySet()
                    .stream()
                    .sorted(Map.Entry.comparingByKey(createListComparator()))
                    .forEach((configEntry) -> initializeEntry(searchString, spec, groups, configEntry));

            map.clear();
        }

        private void initializeEntry(final String searchString, final SpecificationData spec,
                                     final Set<List<String>> groups,
                                     final Map.Entry<List<String>, ConfigEntry> configEntry)
        {
            if (!searchString.isEmpty() && !searchString.isBlank() && !Arrays.stream(searchString.toLowerCase().split(" ")).allMatch(
                    searchEntry -> configEntry.getValue()
                            .getLabel()
                            .getString()
                            .toLowerCase()
                            .contains(searchEntry)))
            {
                return;
            }

            final Set<List<String>> entryGroup = createGroups(configEntry.getKey());
            for (final List<String> group : entryGroup)
            {
                if (groups.add(group))
                {
                    final String label = spec.configSpec().getLevelTranslationKey(group);
                    if (label != null)
                    {
                        this.addEntry(new CategoryEntry(group, Component.translatable(label)));
                    }
                }
            }

            this.addEntry(configEntry.getValue());
        }

        private Set<List<String>> createGroups(final List<String> group)
        {
            if (group.isEmpty())
            {
                return Sets.newHashSet();
            }

            final List<String> groupKeys = new ArrayList<>(group);
            groupKeys.remove(groupKeys.size() - 1);
            final Set<List<String>> groups = Sets.newHashSet();

            final List<String> workingList = Lists.newArrayList();
            for (String s : group)
            {
                workingList.add(s);
                groups.add(new ArrayList<>(workingList));
            }

            return groups;
        }

        private void handleConfigValue(final SpecificationData spec, final Map<List<String>, ConfigEntry> map,
                                       final Object value)
        {
            if (value instanceof AbstractConfig innerConfig)
            {
                innerConfig.valueMap().values().forEach((value1) -> handleConfigValue(spec, map, value1));
                return;
            }

            if (value instanceof ForgeConfigSpec.ConfigValue<?> configValue)
            {
                if (!ConfigGuiScreen.this.initialValues.containsKey(configValue.getPath()))
                {
                    ConfigGuiScreen.this.initialValues.put(configValue.getPath(), configValue.get());
                    ConfigGuiScreen.this.currentValues.put(configValue.getPath(), configValue.get());
                }
                map.put(configValue.getPath(), new ConfigEntry(spec, configValue,
                        new ValueManager(() -> ConfigGuiScreen.this.initialValues.get(configValue.getPath()),
                                () -> ConfigGuiScreen.this.currentValues.get(configValue.getPath()),
                                (v) -> ConfigGuiScreen.this.currentValues.put(configValue.getPath(), v))));
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"}) // Stupid generics.
        public void onSave()
        {
            ConfigGuiScreen.this.currentValues.forEach((entry, value) ->
                    this.children()
                            .stream()
                            .filter(ConfigEntry.class::isInstance)
                            .map(ConfigEntry.class::cast)
                            .filter(e -> e.configValue.getPath().equals(entry))
                            .findFirst()
                            .ifPresent(e -> ((ForgeConfigSpec.ConfigValue) e.configValue).set(value)));

            saveSpecifications();
        }

        @SuppressWarnings({"unchecked", "rawtypes"}) // Stupid generics.
        public void onCancel(Runnable onSucceedRunner)
        {
            if (initialValues.entrySet().stream().allMatch(
                    e -> currentValues.get(e.getKey()).equals(initialValues.get(e.getKey()))))
            {
                onSucceedRunner.run();
                return;
            }

            // Forge Config API Port: use vanilla method instead of Forge's gui layers feature
            Minecraft.getInstance().setScreen(
                    new ConfirmScreen((t) -> {
                        if (!t)
                        {
                            // Forge Config API Port: use vanilla method instead of Forge's gui layers feature
                            Minecraft.getInstance().setScreen(ConfigGuiScreen.this);
                            return;
                        }

                        initialValues.forEach((entry, value) ->
                                this.children().stream()
                                        .filter(ConfigEntry.class::isInstance)
                                        .map(ConfigEntry.class::cast)
                                        .filter(e -> e.configValue.getPath().equals(entry))
                                        .findFirst()
                                        .ifPresent(e -> ((ForgeConfigSpec.ConfigValue) e.configValue).set(value)));

                        saveSpecifications();

                        onSucceedRunner.run();
                    },
                            Component.translatable("forge.configgui.cancel.confirm.title"),
                            Component.translatable("forge.configgui.cancel.confirm.message")
                    ));
        }

        private void saveSpecifications()
        {
            ConfigGuiScreen.this.specs.forEach(spec ->
            {
                // At the moment it is unfeaseable to support config specifications which are synced from the server.
                // This was an effective team decision for 2 reasons:
                // 1. A security risk with a client modifying a config on the server.
                // 2. No client ever knows about the servers common config, as such it is impossible to offer a complete solution.
                if (spec.isSynced()) return;

                if (!spec.configSpec().isLoaded()) return; //Can't save unloaded configs

                spec.configSpec().save();
            });
        }

        public void forEachWidget(final Predicate<ConfigEntry> entryPredicate, final Consumer<ConfigGuiWidget> consumer) {
            this.children()
                    .stream()
                    .filter(ConfigEntry.class::isInstance)
                    .map(ConfigEntry.class::cast)
                    .filter(e -> e.widget != null && entryPredicate.test(e))
                    .forEach(e -> consumer.accept(e.widget));
        }

        public boolean hasEntriesMatching(final Predicate<ConfigEntry> matcher) {
            return this.children()
                    .stream()
                    .filter(ConfigEntry.class::isInstance)
                    .map(ConfigEntry.class::cast)
                    .anyMatch(matcher);
        }

        public void resetToInitial()
        {
            forEachWidget(e -> true, ConfigGuiWidget::resetToInitial);
        }

        public void resetToDefault()
        {
            forEachWidget(e -> true, ConfigGuiWidget::resetToDefault);
        }

        public void resetToDefault(final List<String> pathPrefix)
        {
            forEachWidget(e -> isPrefixList(pathPrefix, e.configValue.getPath()), ConfigGuiWidget::resetToDefault);
        }

        public void resetToInitial(final List<String> pathPrefix)
        {
            forEachWidget(e -> isPrefixList(pathPrefix, e.configValue.getPath()), ConfigGuiWidget::resetToInitial);
        }

        public boolean hasEntriesWhichCanBeResetToInitial(final List<String> pathPrefix)
        {
            return hasEntriesMatching(e -> isPrefixList(pathPrefix, e.configValue.getPath()) && e.canBeResetToInitial());
        }

        public boolean hasEntriesWhichCanBeResetToDefault(final List<String> pathPrefix)
        {
            return hasEntriesMatching(e -> isPrefixList(pathPrefix, e.configValue.getPath()) && e.canBeResetToDefault());
        }

        public boolean isPrefixList(final List<String> prefix, final List<String> candidate)
        {
            if (prefix.size() > candidate.size())
            {
                return false;
            }

            for (int i = 0; i < prefix.size(); i++)
            {
                if (!prefix.get(i).equals(candidate.get(i))) return false;
            }

            return true;
        }
    }

    public class ConfigEntry extends AbstractListEntry
    {
        private final Component label;
        private final List<FormattedCharSequence> labelLines;
        private final ConfigGuiWidget widget;
        private final ForgeConfigSpec.ConfigValue<?> configValue;
        private final ForgeConfigSpec.ValueSpec valueSpec;
        private ImageContentButton resetToInitialButton = null;
        private ImageContentButton resetToDefaultButton = null;

        public ConfigEntry(final SpecificationData spec, final ForgeConfigSpec.ConfigValue<?> configValue,
                           final ValueManager valueManager)
        {
            this.configValue = configValue;
            this.valueSpec = spec.configSpec().getRaw(configValue.getPath());

            final String labelTranslationKey = this.valueSpec.getTranslationKey();
            final String tooltipTranslationKey = labelTranslationKey != null ? labelTranslationKey + ".tooltip" : this.valueSpec.getComment();

            this.label = labelTranslationKey != null ? Component.translatable(labelTranslationKey) : Component.literal(String.join(".", configValue.getPath()));
            this.labelLines = Minecraft.getInstance().font.split(this.label, ConfigGuiScreen.this.width - 270);
            this.tooltip = tooltipTranslationKey != null ? Minecraft.getInstance().font.split(Component.translatable(tooltipTranslationKey), ConfigGuiScreen.this.width / 2) : null;

            final ConfigGuiWidgetFactory factory = configValue.getScreenWidgetFactorySupplier() != null ? configValue.getScreenWidgetFactorySupplier().get() : null;
            this.widget = factory != null ? factory.create(configValue, this.valueSpec, valueManager, spec, this.label) : null;
            if (this.widget != null)
            {
                this.resetToDefaultButton = new ImageContentButton(0, 0, 24, 20, 0, 2, 0, FORGE_RESET_TO_DEFAULT_ICON, 24, 24, botton -> this.widget.resetToDefault(), Component.translatable("forge.configgui.resetToDefault"));
                this.resetToInitialButton = new ImageContentButton(0, 0, 24, 20, 0, 2, 0, FORGE_RESET_TO_INITIAL_ICON, 24, 24, botton -> this.widget.resetToInitial(), Component.translatable("forge.configgui.resetToInitial"));
            }
        }

        @Override
        public void render(@NotNull PoseStack poseStack, int entryIdx, int top, int left, int entryWidth,
                           int entryHeight, int mouseX, int mouseY, boolean isHovered, float partialTick)
        {
            this.renderLabel(poseStack, top, left);
            if (widget != null)
            {
                widget.render(poseStack, top, ConfigGuiScreen.this.width - 200, 122, entryHeight, mouseX, mouseY,
                        isHovered, partialTick);

                this.resetToInitialButton.setActive(canBeResetToInitial());
                this.resetToInitialButton.x = left + entryWidth - 52;
                this.resetToInitialButton.y = top;
                this.resetToInitialButton.render(poseStack, mouseX, mouseY, partialTick);

                this.resetToDefaultButton.setActive(canBeResetToDefault());
                this.resetToDefaultButton.x = left + entryWidth - 26;
                this.resetToDefaultButton.y = top;
                this.resetToDefaultButton.render(poseStack, mouseX, mouseY, partialTick);

                if (widget.isValid())
                {
                    ConfigGuiScreen.this.clearInvalid(this);
                } else
                {
                    ConfigGuiScreen.this.markInvalid(this);
                }
                if (isHovered)
                {
                    if (this.resetToInitialButton.isHoveredOrFocused())
                    {
                        ConfigGuiScreen.this.setTooltip(Minecraft.getInstance().font.split(Component.translatable(
                                "forge.configgui.resetToInitial"), ConfigGuiScreen.this.width / 2));
                    } else if (this.resetToDefaultButton.isHoveredOrFocused())
                    {
                        ConfigGuiScreen.this.setTooltip(Minecraft.getInstance().font.split(Component.translatable(
                                "forge.configgui.resetToDefault"), ConfigGuiScreen.this.width / 2));
                    } else
                    {
                        ConfigGuiScreen.this.setTooltip(widget.getTooltip());
                    }
                }
            }
            this.renderRequiresReloadIndicator(poseStack, mouseX, mouseY, top, left, isHovered);
            this.renderWarning(poseStack, mouseX, mouseY, top, left, isHovered);

            final int maxLabelLineWidth = this.labelLines.stream().mapToInt(Minecraft.getInstance().font::width).max().orElse(0);
            if (isHovered && maxLabelLineWidth > 0 && mouseX >= left + 24 && mouseX <= left + 24 + maxLabelLineWidth)
            {
                ConfigGuiScreen.this.setTooltip(this.tooltip);
            }
        }

        @Override
        public @NotNull List<? extends NarratableEntry> narratables()
        {
            return this.widget != null ? ImmutableList.of(this.widget, this.resetToInitialButton, this.resetToDefaultButton) : ImmutableList.of();
        }

        @Override
        public @NotNull List<? extends GuiEventListener> children()
        {
            return this.widget != null ? ImmutableList.of(this.widget, this.resetToInitialButton, this.resetToDefaultButton) : ImmutableList.of();
        }

        public Component getLabel()
        {
            return label;
        }

        protected void renderLabel(PoseStack poseStack, int top, int left)
        {
            if (this.labelLines.size() > 0)
            {
                Minecraft.getInstance().font.draw(poseStack, this.labelLines.get(0), (float) left + 24, (float) (top + (this.labelLines.size() == 1 ? 5 : 0)), 16777215);
                if (this.labelLines.size() >= 2)
                {
                    Minecraft.getInstance().font.draw(poseStack, this.labelLines.get(1), (float) left + 24, (float) (top + 10), 16777215);
                }
            }
        }

        private void renderWarning(PoseStack stack, int mouseX, int mouseY, int top, int left, final boolean isHovered)
        {
            if (this.widget != null && !this.widget.isValid())
            {
                RenderSystem.setShaderTexture(0, FORGE_EXPERIMENTAL_WARNING_ICON);
                GuiComponent.blit(stack, left - 2, top - 2, 0.0F, 0.0F, 22, 22, 22, 22);
                if (isHovered && mouseX > left + 1 && mouseX < left + 23 && mouseY > top + 1 && mouseY < top + 23)
                {
                    final Component errorComponent = this.widget.getError();
                    List<FormattedCharSequence> tooltip = Minecraft.getInstance().font.split(errorComponent != null ? errorComponent : Component.translatable("forge.configgui.entryInvalid"), ConfigGuiScreen.this.width / 2);
                    ConfigGuiScreen.this.setTooltip(tooltip);
                }
            }
        }

        private void renderRequiresReloadIndicator(PoseStack stack, int mouseX, int mouseY, int top, int left,
                                                   final boolean isHovered)
        {
            if (this.widget != null && this.widget.isValid() && ConfigGuiScreen.this.initialValues.get(this.configValue.getPath()) != null && canBeResetToInitial() && this.valueSpec.needsWorldRestart())
            {
                RenderSystem.setShaderTexture(0, FORGE_NEEDS_WORLD_RELOAD_ICON);
                GuiComponent.blit(stack, left - 2, top - 2, 0.0F, 0.0F, 22, 22, 22, 22);
                if (isHovered && mouseX > left + 1 && mouseX < left + 23 && mouseY > top + 1 && mouseY < top + 23)
                {
                    List<FormattedCharSequence> tooltip = Minecraft.getInstance().font.split(Component.translatable("forge.configgui.requiresWorldRestartToTakeEffect"), ConfigGuiScreen.this.width / 2);
                    ConfigGuiScreen.this.setTooltip(tooltip);
                }
            }
        }

        private boolean canBeResetToDefault()
        {
            return this.widget != null && !Objects.equals(this.widget.getValue(), this.configValue.getDefault());
        }

        private boolean canBeResetToInitial()
        {
            return this.widget != null && !Objects.equals(ConfigGuiScreen.this.initialValues.get(this.configValue.getPath()), ConfigGuiScreen.this.currentValues.get(this.configValue.getPath()));
        }
    }

    public class CategoryEntry extends AbstractListEntry
    {
        private final List<String> pathPrefix;
        private final Component label;

        private final ImageContentButton resetToInitialButton;
        private final ImageContentButton resetToDefaultButton;

        public CategoryEntry(final List<String> pathPrefix, Component header)
        {
            this.pathPrefix = pathPrefix;
            this.label = header;

            this.resetToDefaultButton = new ImageContentButton(0, 0, 24, 20, 0, 2, 0, FORGE_RESET_TO_DEFAULT_ICON, 24, 24, botton -> ConfigGuiScreen.this.configEntryList.resetToDefault(this.pathPrefix), Component.translatable("forge.configgui.resetGroupToDefault"));
            this.resetToInitialButton = new ImageContentButton(0, 0, 24, 20, 0, 2, 0, FORGE_RESET_TO_INITIAL_ICON, 24, 24, botton -> ConfigGuiScreen.this.configEntryList.resetToInitial(this.pathPrefix), Component.translatable("forge.configgui.resetGroupToInitial"));
        }

        @Override
        public void render(@NotNull PoseStack poseStack, int entryIdx, int top, int left, int entryWidth,
                           int entryHeight, int mouseX, int mouseY, boolean isHovered, float partialTick)
        {
            fill(poseStack, left, top, left + entryWidth, top + entryHeight, 0xAE282828);

            GuiComponent.drawString(poseStack, Objects.requireNonNull(ConfigGuiScreen.this.minecraft).font, this.label, left + 5, top + (entryHeight - font.lineHeight + 1) / 2, 16777215);
            this.resetToInitialButton.setActive(ConfigGuiScreen.this.configEntryList.hasEntriesWhichCanBeResetToInitial(this.pathPrefix));
            this.resetToInitialButton.x = left + entryWidth - 52;
            this.resetToInitialButton.y = top;
            this.resetToInitialButton.render(poseStack, mouseX, mouseY, partialTick);

            this.resetToDefaultButton.setActive(ConfigGuiScreen.this.configEntryList.hasEntriesWhichCanBeResetToDefault(this.pathPrefix));
            this.resetToDefaultButton.x = left + entryWidth - 26;
            this.resetToDefaultButton.y = top;
            this.resetToDefaultButton.render(poseStack, mouseX, mouseY, partialTick);

            if (this.resetToInitialButton.isActive() && this.resetToInitialButton.isHoveredOrFocused())
            {
                ConfigGuiScreen.this.setTooltip(ConfigGuiScreen.this.minecraft.font.split(this.resetToInitialButton.getMessage(), ConfigGuiScreen.this.width / 2));
            } else if (this.resetToDefaultButton.isActive() && this.resetToDefaultButton.isHoveredOrFocused())
            {
                ConfigGuiScreen.this.setTooltip(ConfigGuiScreen.this.minecraft.font.split(this.resetToDefaultButton.getMessage(), ConfigGuiScreen.this.width / 2));
            }
        }

        @Override
        public @NotNull List<? extends GuiEventListener> children()
        {
            return ImmutableList.of(this.resetToInitialButton, this.resetToDefaultButton);
        }

        @Override
        public @NotNull List<? extends NarratableEntry> narratables()
        {
            return ImmutableList.of(new NarratableEntry()
            {
                @Override
                public NarratableEntry.@NotNull NarrationPriority narrationPriority()
                {
                    return NarratableEntry.NarrationPriority.HOVERED;
                }

                @Override
                public void updateNarration(@NotNull NarrationElementOutput output)
                {
                    output.add(NarratedElementType.TITLE, CategoryEntry.this.label);
                }
            });
        }
    }
}