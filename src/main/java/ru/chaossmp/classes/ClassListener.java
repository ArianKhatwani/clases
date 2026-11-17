package ru.chaossmp.classes;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.*;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class ClassListener implements Listener {
    private final DiamondSMP plugin;
    private final Map<UUID, Long> lastDamage = new HashMap<>();
    private final Map<UUID, Integer> hitCounts = new HashMap<>();
    private final Map<UUID, Long> lastDodge = new HashMap<>();
    private final Map<UUID, Location> projectileOrigins = new HashMap<>();

    public ClassListener(DiamondSMP plugin) { this.plugin = plugin; }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;
        if (holder.view() == MenuHolder.View.MAIN) {
            int[] slots = {10, 12, 14, 16, 28, 30, 32};
            ClassType[] types = ClassType.values();
            for (int i = 0; i < slots.length; i++) if (slot == slots[i]) {
                if (plugin.getStore().get(player.getUniqueId()) != null) {
                    player.sendMessage(ChatColor.RED + "Вы уже выбрали класс. Сменить его может только администратор.");
                    player.closeInventory(); return;
                }
                plugin.getMenu().openConfirm(player, types[i]); return;
            }
        } else if (holder.view() == MenuHolder.View.CONFIRM) {
            if (slot == 11) {
                if (plugin.getStore().get(player.getUniqueId()) != null) {
                    player.sendMessage(ChatColor.RED + "У вас уже есть класс.");
                } else {
                    plugin.getStore().set(player, holder.type());
                    player.sendMessage(ChatColor.GREEN + "Вы выбрали класс: " + holder.type().coloredName());
                    player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
                }
                player.closeInventory();
            } else if (slot == 15) plugin.getMenu().openMain(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastDamage.remove(id); hitCounts.remove(id); lastDodge.remove(id);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.isCancelled()) return;
        ClassType type = plugin.getStore().get(player.getUniqueId());
        if (type == null) return;
        long now = System.currentTimeMillis();
        Long previousHit = lastDamage.put(player.getUniqueId(), now);
        if (type == ClassType.GUARDIAN) {
            event.setDamage(event.getDamage() * (1.0 - plugin.getConfig().getDouble("combat.guardian.damage-reduction-percent", 12) / 100.0));
            long idle = plugin.getConfig().getLong("combat.guardian.charge-idle-seconds", 8) * 1000L;
            // Первый удар после входа в игру тоже может зарядить щит, если игрок долго не получал урон.
            if (previousHit != null && now - previousHit >= idle) {
                double absorb = plugin.getConfig().getDouble("combat.guardian.charged-hit-absorption-percent", 35) / 100.0;
                event.setDamage(event.getDamage() * (1.0 - absorb));
                fx(player, Particle.WAX_ON, 18);
                player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.8f, 1.3f);
            }
        } else if (type == ClassType.SHADOW || type == ClassType.RIFT) {
            String path = type == ClassType.SHADOW ? "combat.shadow.dodge-chance-percent" : "combat.rift.dodge-chance-percent";
            double chance = plugin.getConfig().getDouble(path, type == ClassType.SHADOW ? 18 : 14);
            long cooldown = type == ClassType.SHADOW ? 650 : 900;
            long last = lastDodge.getOrDefault(player.getUniqueId(), 0L);
            if (now - last >= cooldown && ThreadLocalRandom.current().nextDouble(100) < chance) {
                event.setCancelled(true);
                lastDodge.put(player.getUniqueId(), now);
                if (type == ClassType.SHADOW) {
                    fx(player, Particle.SMOKE, 22);
                    player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.5f);
                } else {
                    fx(player, Particle.PORTAL, 28);
                    fx(player, Particle.REVERSE_PORTAL, 12);
                    player.getWorld().playSound(player.getLocation(), Sound.BLOCK_PORTAL_AMBIENT, 0.7f, 1.6f);
                }
            }
        }
    }

    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (projectile.getShooter() instanceof Player) {
            UUID projectileId = projectile.getUniqueId();
            projectileOrigins.put(projectileId, projectile.getLocation().clone());
            Bukkit.getScheduler().runTaskLater(plugin, () -> projectileOrigins.remove(projectileId), 1200L);
        }
    }

    @EventHandler
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) return;
        Entity damagerEntity = event.getDamager();
        Player attacker = damagerEntity instanceof Player p ? p :
                damagerEntity instanceof Projectile projectile && projectile.getShooter() instanceof Player p ? p : null;

        if (attacker != null) {
            ClassType type = plugin.getStore().get(attacker.getUniqueId());
            if (type == ClassType.BERSERK) {
                double max = attacker.getAttribute(Attribute.MAX_HEALTH) == null ? 20 :
                        attacker.getAttribute(Attribute.MAX_HEALTH).getValue();
                double ratio = attacker.getHealth() / max;
                double threshold = plugin.getConfig().getDouble("combat.berserk.low-health-percent", 0.25);
                double bonus = ratio < threshold ? plugin.getConfig().getDouble("combat.berserk.max-bonus-damage", 5) *
                        (1.0 - ratio / Math.max(threshold, 0.01)) : 0;
                event.setDamage(event.getDamage() + Math.max(0, bonus));
                if (plugin.visualsEnabled()) {
                    attacker.getWorld().spawnParticle(Particle.FLAME, event.getEntity().getLocation().add(0, 1, 0), 8, .25, .35, .25, .01);
                }
            } else if (type == ClassType.SHADOW && event.getEntity() instanceof LivingEntity victim) {
                // Цель смотрит в противоположную сторону от атакующего — удар нанесён со спины.
                Vector toAttacker = attacker.getLocation().toVector().subtract(victim.getLocation().toVector()).normalize();
                Vector facing = victim.getLocation().getDirection().normalize();
                if (facing.dot(toAttacker) < -0.55) event.setDamage(event.getDamage() +
                        plugin.getConfig().getDouble("combat.shadow.backstab-bonus-damage", 3));
            } else if (type == ClassType.THUNDER) {
                int count = hitCounts.merge(attacker.getUniqueId(), 1, Integer::sum);
                if (count % 3 == 0) {
                    event.setDamage(event.getDamage() + plugin.getConfig().getDouble("combat.thunder.third-hit-bonus-damage", 2));
                    Location loc = event.getEntity().getLocation();
                    if (plugin.visualsEnabled()) {
                        loc.getWorld().strikeLightningEffect(loc);
                        loc.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, loc.clone().add(0, 1, 0), 24, .5, .7, .5, .08);
                        loc.getWorld().playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, .35f, 1.7f);
                    }
                }
            }
        }

        if (event.getEntity() instanceof Player victim) lastDamage.put(victim.getUniqueId(), System.currentTimeMillis());

        if (attacker != null && event.getEntity() instanceof LivingEntity target) {
            if (plugin.getStore().get(attacker.getUniqueId()) == ClassType.RANGER && damagerEntity instanceof Projectile projectile) {
                Location origin = projectileOrigins.remove(projectile.getUniqueId());
                double distance = origin == null ? 0 : origin.distanceSquared(target.getLocation());
                double min = plugin.getConfig().getDouble("combat.ranger.long-shot-min-distance", 22);
                if (distance >= min * min) {
                    event.setDamage(event.getDamage() + plugin.getConfig().getDouble("combat.ranger.long-shot-bonus-damage", 3));
                    target.setGlowing(true);
                    int ticks = plugin.getConfig().getInt("combat.ranger.mark-seconds", 4) * 20;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (target.isValid() && !target.isDead()) target.setGlowing(false);
                    }, ticks);
                    if (plugin.visualsEnabled()) target.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, target.getLocation().add(0, 1, 0), 10, .3, .5, .3, .02);
                }
            }
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        projectileOrigins.remove(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        ClassType type = plugin.getStore().get(killer.getUniqueId());
        if (type == null) return;
        switch (type) {
            case BERSERK -> killer.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                    plugin.getConfig().getInt("combat.berserk.kill-speed-seconds", 5) * 20,
                    plugin.getConfig().getInt("combat.berserk.kill-speed-amplifier", 1), false, true, true));
            case SHADOW -> killer.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,
                    plugin.getConfig().getInt("combat.shadow.kill-invisibility-seconds", 3) * 20, 0, false, true, true));
            case ALCHEMIST -> {
                PotionEffectType[] effects = {PotionEffectType.SPEED, PotionEffectType.STRENGTH,
                        PotionEffectType.REGENERATION, PotionEffectType.JUMP_BOOST, PotionEffectType.RESISTANCE};
                PotionEffectType chosen = effects[ThreadLocalRandom.current().nextInt(effects.length)];
                killer.addPotionEffect(new PotionEffect(chosen, plugin.getConfig().getInt("combat.alchemist.buff-seconds", 8) * 20, 0, false, true, true));
                fx(killer, Particle.WITCH, 30);
                killer.getWorld().playSound(killer.getLocation(), Sound.ENTITY_WITCH_DRINK, .7f, 1.4f);
            }
            case RANGER -> {
                fx(killer, Particle.HAPPY_VILLAGER, 24);
                killer.getWorld().playSound(killer.getLocation(), Sound.BLOCK_AZALEA_LEAVES_BREAK, .8f, 1.3f);
            }
            case RIFT -> {
                if (plugin.visualsEnabled()) {
                    Location center = killer.getLocation().add(0, 1, 0);
                    killer.getWorld().spawnParticle(Particle.PORTAL, center, 65, .9, .7, .9, .35);
                    killer.getWorld().spawnParticle(Particle.REVERSE_PORTAL, center, 25, .5, .5, .5, .1);
                    killer.getWorld().playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, .7f, .6f);
                }
            }
            default -> { }
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Player player = event.getPlayer();
        ClassType type = plugin.getStore().get(player.getUniqueId());
        if (type == ClassType.BERSERK && player.getHealth() / Math.max(1.0, player.getAttribute(Attribute.MAX_HEALTH).getValue())
                <= plugin.getConfig().getDouble("combat.berserk.aura-health-percent", .45)) {
            if (plugin.visualsEnabled() && player.getTicksLived() % plugin.getConfig().getInt("visual.ambient-period-ticks", 12) == 0)
                player.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, player.getLocation().add(0, 1, 0), 5, .35, .5, .35, .02);
        } else if (type == ClassType.THUNDER && player.isSprinting()) {
            if (plugin.visualsEnabled() && player.getTicksLived() % 8 == 0)
                player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, player.getLocation().add(0, .8, 0), 3, .35, .35, .35, .02);
        } else if (type == ClassType.RIFT && player.isOnGround() == false && player.getVelocity().getY() > .12) {
            if (plugin.visualsEnabled() && player.getTicksLived() % 5 == 0)
                player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation().subtract(0, .5, 0), 5, .2, .15, .2, .1);
        } else if (type == ClassType.RANGER && player.getTicksLived() % 20 == 0 && isNatureBiome(player.getLocation().getBlock().getBiome())) {
            if (plugin.visualsEnabled()) player.getWorld().spawnParticle(Particle.COMPOSTER, player.getLocation().add(0, 1, 0), 3, .4, .5, .4, .01);
        }
    }

    private boolean isNatureBiome(Biome biome) {
        String name = biome.name();
        return name.contains("FOREST") || name.contains("JUNGLE") || name.contains("TAIGA") ||
                name.contains("MEADOW") || name.contains("GROVE") || name.contains("PLAINS");
    }

    private void fx(Player player, Particle particle, int amount) {
        if (plugin.visualsEnabled()) player.getWorld().spawnParticle(particle, player.getLocation().add(0, 1, 0),
                Math.min(amount, plugin.getConfig().getInt("visual.particles-per-effect", 12) * 2), .45, .6, .45, .04);
    }
}
