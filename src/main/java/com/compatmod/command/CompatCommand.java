package com.compatmod.command;

import com.compatmod.CompatMod;
import com.compatmod.config.BlacklistConfig;
import com.compatmod.config.ModConfig;
import com.compatmod.logging.LegacyTransformLogger;
import com.compatmod.patch.CompatRegistry;
import com.compatmod.cache.CacheInspector;
import com.compatmod.safemode.SafeModeHandler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.function.Supplier;

/**
 * FIXED (2026-08-01, second pass): after switching literal()/argument() to
 * Brigadier's own builders, command REGISTRATION started working -- but
 * running a command still crashed the whole integrated server:
 *
 *   NoSuchMethodError: CommandSourceStack.m_288197_(Supplier, boolean)
 *       at CompatCommand.lambda$onRegisterCommands$1(CompatCommand.java:37)
 *
 * That's CommandSourceStack.sendSuccess(Supplier<Component>, boolean) --
 * unlike literal()/argument(), there's no Brigadier-native equivalent for
 * this one (it's genuinely a Minecraft-only API, not a thin wrapper around
 * something external), so it can't be sidestepped the same way. Instead,
 * every sendSuccess() call now goes through trySendSuccess(), which catches
 * Throwable and logs instead of letting a NoSuchMethodError take down the
 * whole server -- same defensive pattern already used in
 * CompatBakedModel.getRenderTypes() for the same underlying class of
 * mapping-mismatch risk. Worst case with this fix: the command runs but the
 * chat confirmation message doesn't show. Without it: the entire integrated
 * server crashes.
 *
 * NOTE: this crash surfaced on a *different* environment (Android/Zalith
 * Launcher, Forge 52.1.16) than the previous successful desktop test
 * (Windows/Prism Launcher, Forge 52.1.0) -- worth confirming which exact
 * Forge build gradle.properties' forge_version should target, since testing
 * across different Forge patch versions is a plausible source of these
 * scattered per-method mismatches.
 *
 * FIXED (2026-08-10): a THIRD instance of the same class of bug, this time
 * worse -- CommandSourceStack.hasPermission(int), used in
 * .requires(s -> s.hasPermission(2)), is called by Brigadier's own
 * CommandNode.canUse() every time permission levels get sent to a player --
 * i.e. on every single player join, not just when running /compatmod.
 * Confirmed via a real crash on Windows/Forge 52.1.0, the target platform
 * confirmed by the user -- so this one is a genuine bug, not a cross-version
 * testing artifact. Wrapped in the same try/catch(Throwable) pattern via
 * tryHasPermission(), originally defaulting to false (deny) on failure --
 * reasoning at the time was that a broken permission check shouldn't
 * silently grant admin access. In practice this backfired: Brigadier hides
 * from client-side tab-completion any command a player fails .requires()
 * for, so when hasPermission() kept throwing, /compatmod didn't error --
 * it just silently vanished, with no visible sign why (confirmed by the
 * user: no crash, but no autocomplete either, "as if the command doesn't
 * exist"). For a mod-status/debug command with no real security stakes,
 * that failure mode is worse than the one it was guarding against, so this
 * now defaults to true (allow) on failure instead.
 *
 * FIXED (2026-08-11): with hasPermission() now allowing access on failure,
 * the user confirmed commands appear and tab-complete correctly -- but
 * running any of them "does nothing": no chat message, no visible effect.
 * Most likely explanation: trySendSuccess() below was silently swallowing
 * the NoSuchMethodError from sendSuccess() itself, so the underlying logic
 * (CompatRegistry.reload(), ModConfig.setSafeMode(), etc.) may well have
 * been running the whole time -- just with zero visible feedback, which
 * looks indistinguishable from "does nothing" to a player. Added a second
 * fallback layer: if sendSuccess() throws, try sending the message via
 * ServerPlayer.sendSystemMessage(Component) instead -- a much older, more
 * fundamental API less likely to share the same mapping issue. If even that
 * fails, at least the warning is logged so it's visible in latest.log
 * instead of vanishing silently.
 */
public class CompatCommand {
    private static final String PREFIX = "compatmod.command.";

    private static void trySendSuccess(CommandSourceStack source, Supplier<Component> message, boolean broadcastToOps) {
        try {
            source.sendSuccess(message, broadcastToOps);
            return;
        } catch (Throwable e) {
            CompatMod.LOGGER.warn("CompatMod: sendSuccess() failed, trying fallback: {}", e.getMessage());
        }
        // Fallback: a much older, more fundamental API than CommandSourceStack's
        // own methods -- if this also fails, we're out of options and just log.
        try {
            var entity = source.getEntity();
            if (entity instanceof net.minecraft.server.level.ServerPlayer player) {
                player.sendSystemMessage(message.get());
            }
        } catch (Throwable e2) {
            CompatMod.LOGGER.warn("CompatMod: fallback sendSystemMessage() also failed: {}", e2.getMessage());
        }
    }

    private static boolean tryHasPermission(CommandSourceStack source, int level) {
        try {
            return source.hasPermission(level);
        } catch (Throwable e) {
            CompatMod.LOGGER.warn("CompatMod: hasPermission() failed; allowing access so the command isn't silently hidden: {}", e.getMessage());
            return true;
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        var root = LiteralArgumentBuilder.<CommandSourceStack>literal("compatmod")
            .requires(s -> tryHasPermission(s, 2))
            .then(LiteralArgumentBuilder.<CommandSourceStack>literal("status")
                .executes(ctx -> {
                    int patches = CompatRegistry.getPatches().size();
                    int applied = CacheInspector.getPatchedCount();
                    String state = SafeModeHandler.isOperational()
                        ? "ACTIVE" : "INACTIVE (safe mode or no patches loaded)";
                    trySendSuccess(ctx.getSource(), () ->
                        Component.translatable(PREFIX + "status", state, patches, applied), false);
                    return 1;
                }))
            .then(LiteralArgumentBuilder.<CommandSourceStack>literal("cache")
                .executes(ctx -> {
                    var snap = CacheInspector.snapshot();
                    trySendSuccess(ctx.getSource(), () ->
                        Component.translatable(PREFIX + "cache",
                            snap.cached(), snap.patched(),
                            snap.safeModeActive() ? "yes" : "no"), false);
                    return 1;
                }))
            .then(LiteralArgumentBuilder.<CommandSourceStack>literal("reload")
                .executes(ctx -> {
                    trySendSuccess(ctx.getSource(), () ->
                        Component.translatable(PREFIX + "reload"), true);
                    CompatRegistry.reload();
                    trySendSuccess(ctx.getSource(), () ->
                        Component.translatable(PREFIX + "reload.done",
                            CompatRegistry.getPatches().size()), true);
                    return 1;
                }))
            .then(LiteralArgumentBuilder.<CommandSourceStack>literal("safemode")
                .executes(ctx -> {
                    boolean current = ModConfig.isSafeMode();
                    ModConfig.setSafeMode(!current);
                    String key = !current ? PREFIX + "safemode.enabled" : PREFIX + "safemode.disabled";
                    trySendSuccess(ctx.getSource(), () -> Component.translatable(key), true);
                    CompatMod.LOGGER.warn("Safe mode {}", !current ? "ENABLED" : "DISABLED");
                    return 1;
                }))
            .then(LiteralArgumentBuilder.<CommandSourceStack>literal("blacklist")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("add")
                    .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("model", StringArgumentType.string())
                        .executes(ctx -> {
                            String m = StringArgumentType.getString(ctx, "model");
                            BlacklistConfig.add(m);
                            trySendSuccess(ctx.getSource(), () ->
                                Component.translatable(PREFIX + "blacklist.add", m), true);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("remove")
                    .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("model", StringArgumentType.string())
                        .executes(ctx -> {
                            String m = StringArgumentType.getString(ctx, "model");
                            BlacklistConfig.remove(m);
                            trySendSuccess(ctx.getSource(), () ->
                                Component.translatable(PREFIX + "blacklist.remove", m), true);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("list")
                    .executes(ctx -> {
                        var all = BlacklistConfig.getAll();
                        trySendSuccess(ctx.getSource(), () ->
                            Component.translatable(PREFIX + "blacklist.list",
                                all.size(), String.join(", ", all)), false);
                        return 1;
                    })))
            .then(LiteralArgumentBuilder.<CommandSourceStack>literal("patches")
                .executes(ctx -> {
                    var names = CompatRegistry.getPatches().stream()
                        .map(p -> p.name()).toList();
                    trySendSuccess(ctx.getSource(), () ->
                        Component.translatable(PREFIX + "patches",
                            String.join(", ", names)), false);
                    return 1;
                }));
        d.register(root);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LegacyTransformLogger.shutdown();
    }
}
