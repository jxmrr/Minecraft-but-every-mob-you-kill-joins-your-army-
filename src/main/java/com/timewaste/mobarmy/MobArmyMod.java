package com.timewaste.mobarmy;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.LevelResource;

/** Server-side multiplayer army mechanics. Mutant Monsters is OPTIONAL. */
public final class MobArmyMod implements ModInitializer {
    private static final String OWNER="mobarmy.owner.", OWNER_NAME="mobarmy.name.";
    private static final String MARKER="mobarmy_egg", EGG_OWNER="mobarmy_owner";
    private static final String EGG_NAME="mobarmy_owner_name", EGG_TYPE="mobarmy_type";
    private static final ChatFormatting[] COLORS={ChatFormatting.DARK_PURPLE,ChatFormatting.YELLOW,ChatFormatting.RED};
    private static final Map<UUID,Integer> colors=new HashMap<>();
    private static final double TARGET_RANGE=128.0; // 8 chunks, not infinite distance
    private static final double TARGET_RANGE_SQ=TARGET_RANGE*TARGET_RANGE;
    // Owner UUID -> selected enemy player UUID. All enemy mobs of that player share the target.
    private static final Map<UUID,UUID> selectedTargets=new HashMap<>();
    private static final Map<UUID,Long> lastTargetClick=new HashMap<>();
    private static final Map<UUID,Long> lastAttack=new HashMap<>(),lastSteal=new HashMap<>();
    private static MinecraftServer serverInstance;
    private static boolean mutants;

    @Override public void onInitialize() {
        mutants=FabricLoader.getInstance().isModLoaded("mutantmonsters");
        ServerTickEvents.END_SERVER_TICK.register(server->{
            init(server);
            for(ServerPlayer p:server.getPlayerList().getPlayers()) colour(p);
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((victim,damage)->{
            if(!(victim instanceof Mob mob) || !(damage.getEntity() instanceof ServerPlayer player)) return;
            // Only naturally occurring/unclaimed mobs award eggs.
            // Existing army mobs never drop a replacement egg, regardless of who kills them.
            if(ownerOf(mob)!=null) return;
            ItemStack egg=egg(mob.getType(),player.getUUID(),player.getName().getString(),colour(player));
            if(!player.getInventory().add(egg)) player.drop(egg,false);
            tell(player,"You earned a "+mob.getType().getDescription().getString()+" army egg!");
        });

        // Runs BEFORE the player's inventory is dropped. 100% chance if an army egg exists.
        ServerLivingEntityEvents.ALLOW_DEATH.register((victim,damage,amount)->{
            if(victim instanceof ServerPlayer defeated && damage.getEntity() instanceof ServerPlayer killer
               && !killer.getUUID().equals(defeated.getUUID())) stealOnKill(killer,defeated);
            return true;
        });

        // No friendly fire, including mob-fired projectiles; wild mobs are protected.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((victim,damage,amount)->{
            UUID attacker=sourceOwner(damage.getEntity(),damage.getDirectEntity());
            if(attacker==null) return true;
            // A mob with no selected enemy team cannot damage anyone.
            // Neither allied mobs nor the third player's team may be damaged.
            UUID selected=selectedTargets.get(attacker);
            if(selected==null) return false;
            Entity direct=damage.getDirectEntity();
            Entity attackEntity=damage.getEntity();
            Entity source=attackEntity!=null?attackEntity:direct;
            if(source!=null && source.distanceToSqr(victim)>TARGET_RANGE_SQ) return false;
            if(victim instanceof ServerPlayer player)
                return selected.equals(player.getUUID());
            if(victim instanceof Mob mob)
                return selected.equals(ownerOf(mob));
            return false;
        });

        UseBlockCallback.EVENT.register((player,world,hand,hit)->{
            ItemStack held=player.getItemInHand(hand);
            CompoundTag tag=readEgg(held);
            if(tag==null) return InteractionResult.PASS;
            if(world.isClientSide()) return InteractionResult.SUCCESS;
            if(!(player instanceof ServerPlayer p) || !(world instanceof ServerLevel level))
                return InteractionResult.FAIL;
            try {
                String typeId=tag.getStringOr(EGG_TYPE,"");
                EntityType<?> type=BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(typeId)).orElse(null);
                if(type==null) {tell(p,"Unknown mob type: "+typeId);return InteractionResult.FAIL;}
                BlockPos at=hit.getBlockPos().relative(hit.getDirection());
                Entity spawned=type.spawn(level,at,EntitySpawnReason.SPAWN_ITEM_USE);
                if(!(spawned instanceof Mob mob)) return InteractionResult.FAIL;
                own(mob,UUID.fromString(tag.getStringOr(EGG_OWNER,"")),
                    tag.getStringOr(EGG_NAME,"Unknown"));
                if(!p.getAbilities().instabuild) held.shrink(1);
                return InteractionResult.SUCCESS;
            } catch(IllegalArgumentException e) {return InteractionResult.FAIL;}
        });

        // Right-click a player with an empty main hand: select that person's whole team.
        UseEntityCallback.EVENT.register((player,world,hand,entity,hit)->{
            if(hand!=InteractionHand.MAIN_HAND || !player.getItemInHand(hand).isEmpty()
                || !(entity instanceof ServerPlayer enemy) || !(player instanceof ServerPlayer commander))
                return InteractionResult.PASS;
            if(world.isClientSide()) return InteractionResult.SUCCESS;
            return selectTarget(commander,enemy)?InteractionResult.SUCCESS:InteractionResult.PASS;
        });

        // Far-away right-click in the air (up to 8 chunks) with an EMPTY main hand.
        // The server checks the player's aim and any intervening blocks.
        UseItemCallback.EVENT.register((player,world,hand)->{
            if(hand!=InteractionHand.MAIN_HAND || !player.getItemInHand(hand).isEmpty())
                return InteractionResult.PASS;
            Player aimed=findAimedPlayer(player,world);
            if(aimed==null) return InteractionResult.PASS;
            if(world.isClientSide()) return InteractionResult.SUCCESS;
            if(player instanceof ServerPlayer commander && aimed instanceof ServerPlayer enemy)
                return selectTarget(commander,enemy)?InteractionResult.SUCCESS:InteractionResult.PASS;
            return InteractionResult.PASS;
        });

        // Sneak-right-click enemy army mob with emerald: 50%, cooldown 30 seconds.
        UseEntityCallback.EVENT.register((player,world,hand,entity,hit)->{
            if(hand!=InteractionHand.MAIN_HAND || !player.isShiftKeyDown() || !(entity instanceof Mob mob))
                return InteractionResult.PASS;
            ItemStack held=player.getItemInHand(hand);
            if(!held.is(Items.EMERALD)) return InteractionResult.PASS;
            UUID oldOwner=ownerOf(mob);
            if(oldOwner==null || oldOwner.equals(player.getUUID())) return InteractionResult.PASS;
            if(world.isClientSide()) return InteractionResult.SUCCESS;
            if(!(player instanceof ServerPlayer thief) || !(world instanceof ServerLevel level))
                return InteractionResult.FAIL;
            long tick=level.getGameTime();
            long previous=lastSteal.getOrDefault(thief.getUUID(),-600L);
            if(tick-previous<600) {
                tell(thief,"Steal cooldown: "+((600-(tick-previous)+19)/20)+" seconds.");
                return InteractionResult.SUCCESS;
            }
            lastSteal.put(thief.getUUID(),tick);
            if(!thief.getAbilities().instabuild) held.shrink(1);
            String mobName=mob.getType().getDescription().getString();
            String formerName=ownerName(mob);
            ServerPlayer previousPlayer=level.getServer().getPlayerList().getPlayer(oldOwner);
            if(level.getRandom().nextBoolean()) {
                own(mob,thief.getUUID(),thief.getName().getString());
                tell(thief,"SUCCESS: You stole "+formerName+"'s "+mobName+" with an emerald!");
                tell(previousPlayer,thief.getName().getString()+" STOLE your "+mobName+" with an emerald!");
            } else {
                tell(thief,"FAILED: You attempted to steal "+formerName+"'s "+mobName+" (1 emerald used).");
                tell(previousPlayer,thief.getName().getString()+" tried to steal your "+mobName+", but FAILED.");
                // A failed theft must not force an untargeted army into combat.
            }
            return InteractionResult.SUCCESS;
        });

        // Global selection among LOADED mobs and online players in the SAME dimension.
        ServerTickEvents.END_SERVER_TICK.register(server->{
            for(ServerLevel world:server.getAllLevels()) {
            if(world.getGameTime()%10!=0) continue;
            ArrayList<Mob> army=new ArrayList<>();
            for(Entity e:world.getAllEntities())
                if(e instanceof Mob mob && mob.isAlive() && ownerOf(mob)!=null) army.add(mob);
            for(Mob mob:army) pursue(world,mob,army);
            }
        });
    }

    private static void stealOnKill(ServerPlayer killer,ServerPlayer victim) {
        int total=0;
        for(int i=0;i<victim.getInventory().getContainerSize();i++) {
            ItemStack it=victim.getInventory().getItem(i);
            if(readEgg(it)!=null) total+=it.getCount();
        }
        if(total==0) {
            tell(killer,"You killed "+victim.getName().getString()+" but they had no army eggs.");
            tell(victim,killer.getName().getString()+" killed you but stole no army eggs.");
            return;
        }
        int index=killer.level().getRandom().nextInt(total);
        for(int slot=0;slot<victim.getInventory().getContainerSize();slot++) {
            ItemStack it=victim.getInventory().getItem(slot);
            CompoundTag tag=readEgg(it);
            if(tag==null) continue;
            if(index>=it.getCount()) {index-=it.getCount();continue;}
            EntityType<?> type;
            try {type=BuiltInRegistries.ENTITY_TYPE.getOptional(
                Identifier.parse(tag.getStringOr(EGG_TYPE,""))).orElse(null);}
            catch(IllegalArgumentException ex) {type=null;}
            if(type==null) {tell(killer,"Could not steal unknown mob egg.");return;}
            it.shrink(1);
            if(it.isEmpty()) victim.getInventory().setItem(slot,ItemStack.EMPTY);
            ItemStack reward=egg(type,killer.getUUID(),killer.getName().getString(),colour(killer));
            if(!killer.getInventory().add(reward)) killer.drop(reward,false);
            String mob=type.getDescription().getString();
            tell(killer,"You killed "+victim.getName().getString()+" and STOLE their "+mob+" egg!");
            tell(victim,killer.getName().getString()+" killed you and STOLE your "+mob+" egg!");
            return;
        }
    }

    private static ItemStack egg(EntityType<?> type,UUID owner,String name,int colour) {
        Identifier id=EntityType.getKey(type);
        Identifier eggId=Identifier.fromNamespaceAndPath(id.getNamespace(),id.getPath()+"_spawn_egg");
        Item item=BuiltInRegistries.ITEM.getOptional(eggId).orElse(Items.PIG_SPAWN_EGG);
        ItemStack stack=new ItemStack(item);
        CompoundTag tag=new CompoundTag();
        tag.putBoolean(MARKER,true);
        tag.putString(EGG_OWNER,owner.toString());
        tag.putString(EGG_NAME,name);
        tag.putString(EGG_TYPE,id.toString());
        CustomData.set(DataComponents.CUSTOM_DATA,stack,tag);
        stack.set(DataComponents.CUSTOM_NAME,displayName(name,type.getDescription().getString()+" Spawn Egg",colour));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE,true);
        return stack;
    }
    private static CompoundTag readEgg(ItemStack stack) {
        if(stack.isEmpty()) return null;
        CustomData data=stack.get(DataComponents.CUSTOM_DATA);
        if(data==null) return null;
        CompoundTag tag=data.copyTag();
        return tag.getBooleanOr(MARKER,false)?tag:null;
    }
    private static UUID ownerOf(Mob mob) {
        for(String tag:mob.entityTags()) if(tag.startsWith(OWNER)) {
            try {return UUID.fromString(tag.substring(OWNER.length()));}
            catch(IllegalArgumentException ignored) {return null;}
        }
        return null;
    }
    private static String ownerName(Mob mob) {
        for(String tag:mob.entityTags()) if(tag.startsWith(OWNER_NAME))
            return tag.substring(OWNER_NAME.length());
        return "Unknown";
    }
    private static void own(Mob mob,UUID owner,String name) {
        for(String tag:mob.entityTags().toArray(String[]::new))
            if(tag.startsWith(OWNER)||tag.startsWith(OWNER_NAME)) mob.removeTag(tag);
        mob.addTag(OWNER+owner);
        mob.addTag(OWNER_NAME+name);
        mob.setCustomNameVisible(true);
        mob.setPersistenceRequired();
        mob.setTarget(null);
        mob.getNavigation().stop();
        refreshName(mob,owner,name);
    }
    private static Component displayName(String name,String type,int color) {
        return Component.literal(name).withStyle(COLORS[Math.floorMod(color,3)])
            .append(Component.literal("'s "+type).withStyle(ChatFormatting.WHITE));
    }
    private static void refreshName(Mob mob,UUID owner,String name) {
        Component display=displayName(name,mob.getType().getDescription().getString(),
            colors.getOrDefault(owner,0));
        if(!display.equals(mob.getCustomName())) mob.setCustomName(display);
    }
    // Return true only when the target was valid; click again to deactivate targeting.
    private static boolean selectTarget(ServerPlayer commander,ServerPlayer enemy) {
        if(commander.getUUID().equals(enemy.getUUID()) || !commander.isAlive()
           || !enemy.isAlive() || enemy.isSpectator() || commander.level()!=enemy.level()
           || commander.distanceToSqr(enemy)>TARGET_RANGE_SQ) return false;
        long tick=commander.level().getGameTime();
        if(tick-lastTargetClick.getOrDefault(commander.getUUID(),-100L)<5) return true;
        lastTargetClick.put(commander.getUUID(),tick);
        UUID previous=selectedTargets.get(commander.getUUID());
        if(enemy.getUUID().equals(previous)) {
            selectedTargets.remove(commander.getUUID());
            tell(commander,"TARGET MODE OFF. Your army is passive.");
        } else {
            selectedTargets.put(commander.getUUID(),enemy.getUUID());
            tell(commander,"TARGET MODE ON: "+enemy.getName().getString()
                +" and their entire army. Attack range: 8 chunks.");
            tell(enemy,commander.getName().getString()+" has targeted you and your army!");
        }
        return true;
    }

    // Raycast the right-clicking player's view to find an enemy player at a distance.
    private static Player findAimedPlayer(Player observer,Level level) {
        Vec3 origin=observer.getEyePosition();
        Vec3 end=origin.add(observer.getLookAngle().scale(TARGET_RANGE));
        Player best=null;
        double bestDist=TARGET_RANGE_SQ;
        for(Player other:level.players()) {
            if(other.getUUID().equals(observer.getUUID())
                || !other.isAlive() || other.isSpectator()
                || other.distanceToSqr(observer)>TARGET_RANGE_SQ) continue;
            Optional<Vec3> intersection=other.getBoundingBox().inflate(0.35).clip(origin,end);
            if(intersection.isEmpty()) continue;
            Vec3 point=intersection.get();
            double dist=origin.distanceToSqr(point);
            if(dist>=bestDist) continue;
            if(level.clip(new ClipContext(origin,point,ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,observer)).getType()==HitResult.Type.BLOCK) continue;
            best=other;
            bestDist=dist;
        }
        return best;
    }

    private static void pursue(ServerLevel world,Mob mob,List<Mob> army) {
        UUID owner=ownerOf(mob);
        if(owner==null||!mob.isAlive()) return;
        refreshName(mob,owner,ownerName(mob));
        UUID targetTeam=selectedTargets.get(owner);
        if(targetTeam==null) {
            mob.setTarget(null);
            mob.getNavigation().stop();
            return;
        }

        // Choose the nearest eligible enemy in the SELECTED TEAM ONLY:
        // the targeted player OR an army mob carrying the target player's owner UUID.
        LivingEntity nearest=null;
        double nearestDistance=TARGET_RANGE_SQ;
        ServerPlayer enemyPlayer=world.getServer().getPlayerList().getPlayer(targetTeam);
        if(enemyPlayer!=null && enemyPlayer.level()==world && enemyPlayer.isAlive()
           && !enemyPlayer.isSpectator()) {
            double d=mob.distanceToSqr(enemyPlayer);
            if(d<=nearestDistance) { nearestDistance=d; nearest=enemyPlayer; }
        }
        for(Mob enemy:army) {
            if(enemy==mob||!enemy.isAlive()||!targetTeam.equals(ownerOf(enemy))) continue;
            double d=mob.distanceToSqr(enemy);
            if(d<=nearestDistance) { nearestDistance=d; nearest=enemy; }
        }
        if(nearest==null) {
            mob.setTarget(null);
            mob.getNavigation().stop();
            return;
        }

        // NEVER teleport the mob. It stays at its spawn location until it walks
        // or uses its own vanilla/mutant movement logic, even when its owner is far away.
        boolean mutant=mutants && EntityType.getKey(mob.getType()).getNamespace().equals("mutantmonsters");
        mob.setTarget(nearest);
        if(!mutant||mob.getNavigation().isDone())
            mob.getNavigation().moveTo(nearest,mutant?1.0:1.3);
        mob.getLookControl().setLookAt(nearest,30,30);
        if(mutant) return; // mutant monsters keep their specialized attacks
        double reach=Math.max(2.3,mob.getBbWidth()*0.5+1.5);
        if(mob.distanceToSqr(nearest)<=reach*reach && mob.hasLineOfSight(nearest)) {
            long tick=world.getGameTime();
            if(tick-lastAttack.getOrDefault(mob.getUUID(),-999L)>=20) {
                lastAttack.put(mob.getUUID(),tick);
                nearest.hurtServer(world,world.damageSources().mobAttack(mob),3f);
            }
        }
    }
    private static UUID sourceOwner(Entity attacker,Entity direct) {
        if(attacker instanceof Mob mob) return ownerOf(mob);
        if(attacker instanceof Projectile projectile && projectile.getOwner() instanceof Mob mob)
            return ownerOf(mob);
        if(direct instanceof Mob mob) return ownerOf(mob);
        if(direct instanceof Projectile projectile && projectile.getOwner() instanceof Mob mob)
            return ownerOf(mob);
        return null;
    }
    private static void tell(ServerPlayer player,String message) {
        if(player!=null) player.sendSystemMessage(Component.literal("[Mob Army] "+message)
            .withStyle(ChatFormatting.GOLD));
    }
    private static int colour(ServerPlayer player) {
        init(player.level().getServer());
        Integer assigned=colors.get(player.getUUID());
        if(assigned!=null) return assigned;
        Set<Integer> occupied=new HashSet<>(colors.values());
        int result=0;
        for(int i=0;i<3;i++) if(!occupied.contains(i)) {result=i;break;}
        colors.put(player.getUUID(),result);
        store(player.level().getServer());
        tell(player,"Your army colour is "+new String[]{"PURPLE","YELLOW","RED"}[result]+".");
        return result;
    }
    private static Path config(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("mobarmy-colors.properties");
    }
    private static void init(MinecraftServer server) {
        if(server==serverInstance) return;
        serverInstance=server;
        colors.clear();lastAttack.clear();lastSteal.clear();selectedTargets.clear();lastTargetClick.clear();
        Properties p=new Properties();
        if(!Files.isRegularFile(config(server))) return;
        try(InputStream stream=Files.newInputStream(config(server))) {
            p.load(stream);
            for(String id:p.stringPropertyNames())
                try {colors.put(UUID.fromString(id),Integer.parseInt(p.getProperty(id)));}
                catch(IllegalArgumentException ignored) {}
        } catch(IOException ex) {System.err.println("[Mob Army] Colour read failed: "+ex);}
    }
    private static void store(MinecraftServer server) {
        Properties props=new Properties();
        colors.forEach((id,colour)->props.setProperty(id.toString(),Integer.toString(colour)));
        try(OutputStream out=Files.newOutputStream(config(server))) {
            props.store(out,"Mob Army colours");
        } catch(IOException ex) {System.err.println("[Mob Army] Colour save failed: "+ex);}
    }
}
