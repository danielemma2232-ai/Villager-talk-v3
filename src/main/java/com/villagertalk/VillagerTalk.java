package com.villagertalk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

public class VillagerTalk implements ModInitializer {
    static long tick = 0;
    static final double HEAR_RANGE = 8.0;

    record Task(long at, Runnable run) {}
    static final List<Task> tasks = new ArrayList<>();

    @Override
    public void onInitialize() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
                onChat(sender, message.signedContent()));
        ServerLivingEntityEvents.AFTER_DEATH.register(VillagerTalk::onDeath);
        ServerTickEvents.END_SERVER_TICK.register(VillagerTalk::onTick);
        // keep the player's marriage when they die and respawn
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            for (String t : new ArrayList<>(oldPlayer.getTags())) {
                if (t.startsWith("vt_wed|") || t.startsWith("vt_phome|")) newPlayer.addTag(t);
            }
        });
        // remember the player's own bed so a spouse can move in
        EntitySleepEvents.START_SLEEPING.register((entity, sleepingPos) -> {
            if (entity instanceof ServerPlayer p && p.level() instanceof ServerLevel sl
                    && sl.dimension() == Level.OVERWORLD) {
                BlockPos head = Brain.headOf(sl, sleepingPos);
                Brain.setTag(p, "vt_phome|", head.getX() + "," + head.getY() + "," + head.getZ());
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    static List<Entity> villagersNear(Level level, Entity centre, double r) {
        AABB box = centre.getBoundingBox().inflate(r);
        return level.getEntities((Entity) null, box,
                e -> e.getType() == EntityType.VILLAGER && e.isAlive());
    }

    static Entity nearest(List<Entity> list, Entity to) {
        Entity best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : list) {
            double d = e.distanceToSqr(to);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    /** Villager says something to everyone within earshot, after a short "thinking" delay. */
    static void say(ServerLevel sl, Entity v, String text, int delayTicks) {
        tasks.add(new Task(tick + delayTicks, () -> {
            if (!v.isAlive()) return;
            Brain.Persona pe = Brain.persona(v);
            Component c = Component.literal("<" + pe.name + "> ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(text).withStyle(ChatFormatting.WHITE));
            for (ServerPlayer pl : sl.players()) {
                if (pl.distanceToSqr(v) < 24.0 * 24.0) pl.sendSystemMessage(c);
            }
        }));
    }

    // ------------------------------------------------------------------- chat

    static void onChat(ServerPlayer sender, String text) {
        if (text == null || text.isBlank()) return;
        Level lvl = sender.level();
        if (!(lvl instanceof ServerLevel sl)) return;

        List<Entity> near = villagersNear(sl, sender, HEAR_RANGE);
        if (near.isEmpty()) return;

        String norm = Brain.norm(text);
        String pad = " " + norm + " ";
        boolean gossip = Brain.looksLikeGossip(text);

        Entity target = null;
        // 1. "Bob, hello" -> Bob answers
        for (Entity e : near) {
            String nm = Brain.persona(e).name.toLowerCase(Locale.ROOT);
            if (norm.equals(nm) || norm.startsWith(nm + " ")) {
                target = e;
                break;
            }
        }
        // 2. "Bob said you are stupid" -> someone ELSE hears it, not Bob
        if (target == null && gossip) {
            double bd = Double.MAX_VALUE;
            for (Entity e : near) {
                String nm = Brain.persona(e).name.toLowerCase(Locale.ROOT);
                if (pad.contains(" " + nm + " ")) continue;
                double d = e.distanceToSqr(sender);
                if (d < bd) {
                    bd = d;
                    target = e;
                }
            }
        }
        // 3. a villager's name appears somewhere in the message
        if (target == null && !gossip) {
            for (Entity e : near) {
                String nm = Brain.persona(e).name.toLowerCase(Locale.ROOT);
                if (pad.contains(" " + nm + " ")) {
                    target = e;
                    break;
                }
            }
        }
        if (target == null) target = nearest(near, sender);

        Brain.Persona me = Brain.persona(target);
        String reply = Brain.respond(new Brain.Ctx(sender, target, me, sl, text, tick));
        if (reply != null) say(sl, target, reply, 14 + Brain.R.nextInt(16));
    }

    // ------------------------------------------------------------------ death

    static void onDeath(LivingEntity dead, DamageSource source) {
        Level lvl = dead.level();
        if (!(lvl instanceof ServerLevel sl)) return;

        if (dead instanceof ServerPlayer p) {
            Brain.deaths.merge(p.getUUID(), 1, Integer::sum);
            Entity v = nearest(villagersNear(sl, p, 14), p);
            if (v != null) {
                say(sl, v, Brain.pick(
                        "Oh no, " + p.getName().getString() + " has fallen! Hrmm... they will rise again, I hope.",
                        "A moment of silence for " + p.getName().getString() + "... Hrmm.",
                        "They died! Quick, somebody ring the bell!"), 20);
            }
            return;
        }

        if (dead.getType() == EntityType.VILLAGER) {
            Brain.villagerDeaths++;
            Brain.Persona gone = Brain.personas.get(dead.getUUID());
            String goneName = gone != null ? gone.name : "a neighbour";

            // the husband / wife / player left behind
            Brain.Spouse sp = Brain.spouseOf(dead);
            if (sp != null) {
                if (sp.player()) {
                    ServerPlayer widow = sl.getServer().getPlayerList().getPlayer(sp.id());
                    if (widow != null) {
                        Brain.clearTag(widow, "vt_wed|");
                        widow.sendSystemMessage(Component.literal(goneName + ", your spouse, has died.")
                                .withStyle(ChatFormatting.GRAY));
                    }
                } else {
                    Entity other = sl.getEntity(sp.id());
                    if (other != null && other.isAlive()) {
                        Brain.clearTag(other, "vt_sp|");
                        Brain.setTag(other, "vt_lost|", goneName);
                        Brain.persona(other).setMood(Brain.Mood.SAD, tick, 48000);
                        say(sl, other, goneName + "... my " + (gone != null && gone.female ? "wife" : "husband")
                                + "... no... *sobs*", 30);
                    }
                }
            }

            List<Entity> witnesses = villagersNear(sl, dead, 16);
            boolean murder = source.getEntity() instanceof ServerPlayer;
            boolean first = true;
            for (Entity w : witnesses) {
                if (w == dead) continue;
                Brain.Persona wp = Brain.persona(w);
                if (murder) {
                    wp.setMood(Brain.Mood.ANGRY, tick, 12000);
                    Brain.addTrust(w, wp, -3);
                } else {
                    wp.setMood(Brain.R.nextBoolean() ? Brain.Mood.SAD : Brain.Mood.SCARED, tick, 6000);
                }
                if (first) {
                    first = false;
                    say(sl, w, murder
                            ? "You killed " + goneName + "! Murderer! Stay away from us!"
                            : Brain.pick("No... " + goneName + " is gone. Hrmm.",
                                    goneName + " has died! Everyone, be careful!"), 25);
                }
            }
        }
    }

    // ------------------------------------------------------------------- tick

    static void onTick(MinecraftServer server) {
        tick++;

        for (int i = 0; i < tasks.size(); ) {
            Task t = tasks.get(i);
            if (t.at() <= tick) {
                tasks.remove(i);
                t.run().run();
            } else {
                i++;
            }
        }

        if (tick % 10 == 0) updateFollowers(server);
        if (tick % 200 == 0) matchmake(server);
        if (tick % 1200 == 0) proactiveQuestions(server);
    }

    static void updateFollowers(MinecraftServer server) {
        for (Map.Entry<UUID, Brain.Persona> en : Brain.personas.entrySet()) {
            Brain.Persona pe = en.getValue();
            if (pe.follow == null) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(pe.follow);
            if (p == null) continue;
            Level l = p.level();
            if (!(l instanceof ServerLevel sl)) continue;
            Entity v = sl.getEntity(en.getKey());
            if (!(v instanceof Mob mob) || !v.isAlive()) continue;
            double d2 = mob.distanceToSqr(p);
            if (d2 > 9.0 && d2 < 40.0 * 40.0) {
                mob.getNavigation().moveTo(p.getX(), p.getY(), p.getZ(), 1.1D);
            } else if (d2 <= 9.0) {
                mob.getNavigation().stop();
            }
        }
    }

    /**
     * Pairs up single villagers (a man and a woman who live close together) so that BOTH remember each
     * other, and keeps married villagers on the same bed / house.
     */
    static void matchmake(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Level l = p.level();
            if (!(l instanceof ServerLevel sl)) continue;
            List<Entity> vs = villagersNear(sl, p, 48);
            if (vs.size() > 40) vs = vs.subList(0, 40);

            List<Entity> singles = new ArrayList<>();
            for (Entity e : vs) {
                Brain.persona(e);
                if (Brain.tagVal(e, "vt_home|") != null) Brain.applyHome(sl, e);
                if (Brain.spouseOf(e) != null) {
                    Brain.shareWithSpouse(sl, e);
                    continue;
                }
                if (Brain.isBaby(e)) continue;
                if (Brain.tagVal(e, "vt_single|") != null) continue;
                singles.add(e);
            }

            for (Entity a : singles) {
                if (Brain.spouseOf(a) != null) continue;
                boolean aFemale = Brain.persona(a).female;
                Entity best = null;
                double bd = 24.0 * 24.0;
                for (Entity b : singles) {
                    if (a == b || Brain.spouseOf(b) != null) continue;
                    if (Brain.persona(b).female == aFemale) continue;
                    double d = a.distanceToSqr(b);
                    if (d < bd) {
                        bd = d;
                        best = b;
                    }
                }
                if (best != null) Brain.marryVillagers(a, best);
            }
        }
    }

    /** Now and then a villager walks up to a nearby player and asks something. */
    static void proactiveQuestions(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (Brain.pending.containsKey(p.getUUID())) continue;
            if (Brain.R.nextDouble() > 0.3) continue;
            Level l = p.level();
            if (!(l instanceof ServerLevel sl)) continue;
            Entity v = nearest(villagersNear(sl, p, 6), p);
            if (v == null) continue;
            Brain.Persona me = Brain.persona(v);
            say(sl, v, Brain.ask(p, me, tick), 10);
        }
    }
}
