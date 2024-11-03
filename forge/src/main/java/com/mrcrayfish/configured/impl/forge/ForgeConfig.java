package com.mrcrayfish.configured.impl.forge;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.mrcrayfish.configured.Constants;
import com.mrcrayfish.configured.api.ActionResult;
import com.mrcrayfish.configured.api.ConfigType;
import com.mrcrayfish.configured.api.ExecutionContext;
import com.mrcrayfish.configured.api.IConfigEntry;
import com.mrcrayfish.configured.api.IConfigValue;
import com.mrcrayfish.configured.api.IModConfig;
import com.mrcrayfish.configured.client.ClientSessionData;
import com.mrcrayfish.configured.network.ForgeNetwork;
import com.mrcrayfish.configured.network.message.play.MessageSyncForgeConfig;
import com.mrcrayfish.configured.util.ConfigHelper;
import com.mrcrayfish.configured.util.ForgeConfigHelper;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class ForgeConfig implements IModConfig
{
    protected static final EnumMap<ModConfig.Type, ConfigType> TYPE_RESOLVER = Util.make(new EnumMap<>(ModConfig.Type.class), (map) -> {
        map.put(ModConfig.Type.CLIENT, ConfigType.CLIENT);
        map.put(ModConfig.Type.COMMON, ConfigType.UNIVERSAL);
        map.put(ModConfig.Type.SERVER, ConfigType.WORLD_SYNC);
    });

    protected final ModConfig config;
    protected final ForgeConfigSpec spec;
    protected final List<ForgeValueEntry> allConfigValues;

    public ForgeConfig(ModConfig config, ForgeConfigSpec spec)
    {
        Objects.requireNonNull(spec, "ForgeConfigSpec cannot be null");
        this.config = config;
        this.spec = spec;
        this.allConfigValues = getAllConfigValues(this.spec);
    }

    @Override
    public ConfigType getType()
    {
        return TYPE_RESOLVER.get(this.config.getType());
    }

    @Override
    public String getFileName()
    {
        return this.config.getFileName();
    }

    @Override
    public String getModId()
    {
        return this.config.getModId();
    }

    @Override
    public IConfigEntry createRootEntry()
    {
        return new ForgeFolderEntry(this.spec.getValues(), this.spec);
    }

    @Override
    public ActionResult update(IConfigEntry entry)
    {
        Set<IConfigValue<?>> changedValues = ConfigHelper.getChangedValues(entry);
        if(!changedValues.isEmpty())
        {
            CommentedConfig newConfig = CommentedConfig.copy(this.config.getConfigData());
            changedValues.forEach(value ->
            {
                if(value instanceof ForgeValue<?> forge)
                {
                    if(forge instanceof ForgeListValue<?> forgeList)
                    {
                        List<?> converted = forgeList.getConverted();
                        if(converted != null)
                        {
                            newConfig.set(forge.configValue.getPath(), converted);
                            return;
                        }
                    }
                    newConfig.set(forge.configValue.getPath(), value.get());
                }
            });
            this.config.getConfigData().putAll(newConfig);
        }

        if(this.getType() == ConfigType.WORLD_SYNC)
        {
            if(!ConfigHelper.isPlayingGame())
            {
                // Unload server configs since still in main menu
                this.config.getHandler().unload(this.config.getFullPath().getParent(), this.config);
                ForgeConfigHelper.setForgeConfigData(this.config, null);
            }
            else
            {
                this.syncToServer();
            }

        }
        else if(!changedValues.isEmpty())
        {
            Constants.LOG.info("Sending config reloading event for {}", this.config.getFileName());
            this.spec.afterReload();
            ForgeConfigHelper.fireForgeConfigEvent(this.config, new ModConfigEvent.Reloading(this.config));
        }
        return ActionResult.success();
    }

    @Override
    public ActionResult loadWorldConfig(Path path)
    {
        if(this.config.getConfigData() == null)
        {
            try
            {
                final CommentedFileConfig data = this.config.getHandler().reader(path).apply(this.config);
                ForgeConfigHelper.setForgeConfigData(this.config, data);
                return ActionResult.success();
            }
            catch(Exception e)
            {
                Constants.LOG.error("Failed to load Forge world", e);
                return ActionResult.fail();
            }
        }
        return ActionResult.success();
    }

    @Override
    public void stopEditing(boolean changed)
    {
        // Attempts to unload the server config if player simply just went back
        if(this.config != null && this.getType() == ConfigType.WORLD)
        {
            if(!ConfigHelper.isPlayingGame())
            {
                // Unload server configs since still in main menu
                this.config.getHandler().unload(this.config.getFullPath().getParent(), this.config);
                ForgeConfigHelper.setForgeConfigData(this.config, null);
            }
        }
    }

    @Override
    public boolean isChanged()
    {
        // Block world configs since the path is dynamic
        if(this.getType().isWorld() && this.config.getConfigData() == null)
            return false;

        // Check if any config value doesn't equal it's default
        return this.allConfigValues.stream().anyMatch(entry -> {
            return !Objects.equals(entry.value.get(), entry.spec.getDefault());
        });
    }

    @Override
    public Optional<Runnable> restoreDefaultsTask()
    {
        // Block unloaded world configs since the path is dynamic
        if(this.getType().isWorld() && this.config.getConfigData() == null)
            return Optional.empty();

        return Optional.of(() -> {
            // Creates a copy of the config data then pushes all at once to avoid multiple IO ops
            CommentedConfig newConfig = CommentedConfig.copy(this.config.getConfigData());
            this.allConfigValues.forEach(entry -> newConfig.set(entry.value.getPath(), entry.spec.getDefault()));
            this.config.getConfigData().putAll(newConfig);
            // Finally clear cache of all config values
            this.allConfigValues.forEach(pair -> pair.value.clearCache());
        });
    }

    @Override
    public ActionResult canPlayerEdit(@Nullable Player player)
    {
        ExecutionContext context = new ExecutionContext(player);
        if(context.isClient())
        {
            return switch(this.config.getType()) {
                case CLIENT, COMMON -> {
                    if(context.isMainMenu() || context.isLocalPlayer()) {
                        yield ActionResult.success();
                    }
                    yield ActionResult.fail();
                }
                case SERVER -> {
                    if(context.isMainMenu() || context.isSingleplayer()) {
                        yield ActionResult.success();
                    }
                    if(context.isPlayingOnLan()) {
                        if(context.isIntegratedServerOwnedByPlayer()) {
                            yield ActionResult.fail(Component.translatable("configured.gui.no_editing_published_lan_server"));
                        } else {
                            yield ActionResult.fail(Component.translatable("configured.gui.lan_server"));
                        }
                    }
                    if(context.isPlayingOnRemoteServer()) {
                        if(context.isPlayerAnOperator() && context.isDeveloperPlayer()) {
                            yield ActionResult.success();
                        } else {
                            yield ActionResult.fail(Component.translatable("configured.gui.no_developer_status"));
                        }
                    }
                    yield ActionResult.fail();
                }
            };
        }
        else if(context.isDedicatedServer())
        {
            return switch(this.config.getType()) {
                case CLIENT, COMMON -> ActionResult.fail();
                case SERVER -> {
                    if(context.isPlayerAnOperator() && context.isDeveloperPlayer()) {
                        yield ActionResult.success();
                    }
                    yield ActionResult.fail();
                }
            };
        }
        return ActionResult.fail();
    }

    private void syncToServer()
    {
        if(this.config == null)
            return;

        if(!ConfigHelper.isPlayingGame())
            return;

        if(!ConfigHelper.isConfiguredInstalledOnServer())
            return;

        if(this.getType() != ConfigType.WORLD_SYNC) // Forge only supports this type
            return;

        // Checked on server too
        Player player = ConfigHelper.getClientPlayer();
        if(!ConfigHelper.isOperator(player) || !ClientSessionData.isDeveloper())
            return;

        try
        {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            TomlFormat.instance().createWriter().write(this.config.getConfigData(), stream);
            ForgeNetwork.getChannel().send(new MessageSyncForgeConfig(this.config.getFileName(), stream.toByteArray()), PacketDistributor.SERVER.noArg());
            stream.close();
        }
        catch(IOException e)
        {
            Constants.LOG.error("Failed to close byte stream when sending config to server");
        }
    }

    protected static List<ForgeValueEntry> getAllConfigValues(ForgeConfigSpec spec)
    {
        return ForgeConfigHelper.gatherAllForgeConfigValues(spec.getValues(), spec).stream().map(pair -> new ForgeValueEntry(pair.getLeft(), pair.getRight())).toList();
    }

    protected record ForgeValueEntry(ForgeConfigSpec.ConfigValue<?> value, ForgeConfigSpec.ValueSpec spec) {}
}
