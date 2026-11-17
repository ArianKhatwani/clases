
package ru.chaossmp.classes;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Biome;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.potion.*;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class ClassListener implements Listener {

    private final ChaosClasses plugin;

    private final Map<UUID, Long> lastDamage = new HashMap<>();
    private final Map<UUID, Integer> hitCounts = new HashMap<>();
    private final Map<UUID, Long> lastDodge = new HashMap<>();
    private final Map<UUID, Location> projectileOrigins = new HashMap<>();

    public ClassListener(ChaosClasses plugin) {
        this.plugin = plugin;
    }

    // =====================================================
    // GUI
    // =====================================================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {

        if (!(event.getInventory().getHolder() instanceof MenuHolder holder))
            return;

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player))
            return;

        int slot = event.getRawSlot();

        if (slot < 0 || slot >= event.getInventory().getSize())
            return;

        if (holder.view() == MenuHolder.View.MAIN) {

            int[] slots = {10, 12, 14, 16, 28, 30, 32};
            ClassType[] types = ClassType.values();

            for (int i = 0; i < slots.length; i++) {

                if (slot != slots[i])
                    continue;

                if (plugin.getStore().get(player.getUniqueId()) != null) {
                    player.sendMessage(ChatColor.RED +
                            "Вы уже выбрали класс!");
                    player.closeInventory();
                    return;
                }

                plugin.getMenu().openConfirm(player, types[i]);
                return;
            }

        } else if (holder.view() == MenuHolder.View.CONFIRM) {

            if (slot == 11) {

                if (plugin.getStore().get(player.getUniqueId()) != null) {
                    player.sendMessage(ChatColor.RED +
                            "У вас уже есть класс!");
                    player.closeInventory();
                    return;
                }

                plugin.getStore().set(player, holder.type());

                player.sendMessage(ChatColor.GREEN +
                        "Вы выбрали класс: " +
                        holder.type().coloredName());

                player.playSound(
                        player.getLocation(),
                        Sound.UI_TOAST_CHALLENGE_COMPLETE,
                        0.8f,
                        1.2f
                );

                player.closeInventory();

            } else if (slot == 15) {

                plugin.getMenu().openMain(player);
            }
        }
    }

    // =====================================================
    // PLAYER DATA
    // =====================================================

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        lastDamage.put(
                event.getPlayer().getUniqueId(),
                System.currentTimeMillis()
        );
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {

        UUID id = event.getPlayer().getUniqueId();

        lastDamage.remove(id);
        hitCounts.remove(id);
        lastDodge.remove(id);
    }

    // =====================================================
    // PROJECTILE TRACKING
    // =====================================================

    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent event) {

        Projectile projectile = event.getEntity();

        if (projectile.getShooter() instanceof Player) {
            projectileOrigins.put(
                    projectile.getUniqueId(),
                    projectile.getLocation().clone()
            );
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        projectileOrigins.remove(event.getEntity().getUniqueId());
    }

    // =====================================================
    // DEFENSIVE PASSIVES
    // =====================================================

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent event) {

        if (!(event.getEntity() instanceof Player player))
            return;

        if (event.isCancelled())
            return;

        ClassType type = plugin.getStore().get(player.getUniqueId());

        if (type == null)
            return;

        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        long previousDamage = lastDamage.getOrDefault(id, now);

        // -------------------------
        // GUARDIAN
        // -------------------------

        if (type == ClassType.GUARDIAN) {

            double reduction = plugin.getConfig().getDouble(
                    "combat.guardian.damage-reduction-percent", 12
            );

            event.setDamage(
                    event.getDamage() * (1.0 - reduction / 100.0)
            );

            long idleTime = plugin.getConfig().getLong(
                    "combat.guardian.charge-idle-seconds", 8
            ) * 1000L;

            if (now - previousDamage >= idleTime) {

                double absorption = plugin.getConfig().getDouble(
                        "combat.guardian.charged-hit-absorption-percent", 35
                );

                event.setDamage(
                        event.getDamage() * (1.0 - absorption / 100.0)
                );

                fx(player, Particle.WAX_ON, 18);

                player.getWorld().playSound(
                        player.getLocation(),
                        Sound.ITEM_SHIELD_BLOCK,
                        0.8f,
                        1.3f
                );
            }
        }

        // -------------------------
        // SHADOW / RIFT DODGE
        // -------------------------

        if (type == ClassType.SHADOW || type == ClassType.RIFT) {

            String path = type == ClassType.SHADOW
                    ? "combat.shadow.dodge-chance-percent"
                    : "combat.rift.dodge-chance-percent";

            double chance = plugin.getConfig().getDouble(
                    path,
                    type == ClassType.SHADOW ? 18 : 14
            );

            long cooldown = type == ClassType.SHADOW ? 650 : 900;

            long last = lastDodge.getOrDefault(id, 0L);

            if (now - last >= cooldown &&
                    ThreadLocalRandom.current().nextDouble(100) < chance) {

                event.setCancelled(true);

                lastDodge.put(id, now);

                if (type == ClassType.SHADOW) {

                    fx(player, Particle.SMOKE, 22);

                    player.getWorld().playSound(
                            player.getLocation(),
                            Sound.ENTITY_ENDERMAN_TELEPORT,
                            0.7f,
                            1.5f
                    );

                } else {

                    fx(player, Particle.PORTAL, 28);
                    fx(player, Particle.REVERSE_PORTAL, 15);

                    player.getWorld().playSound(
                            player.getLocation(),
                            Sound.BLOCK_PORTAL_AMBIENT,
                            0.7f,
                            1.6f
                    );
                }
            }
        }

        lastDamage.put(id, now);
    }

    // =====================================================
    // ATTACK PASSIVES
    // =====================================================

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {

        if (event.isCancelled())
            return;

        Entity damager = event.getDamager();

        Player attacker = null;

        if (damager instanceof Player player) {
            attacker = player;
        } else if (damager instanceof Projectile projectile &&
                projectile.getShooter() instanceof Player player) {
            attacker = player;
        }

        if (attacker == null)
            return;

        Entity target = event.getEntity();

        ClassType type = plugin.getStore().get(attacker.getUniqueId());

        if (type == null)
            return;

        // -------------------------
        // BERSERK
        // -------------------------

        if (type == ClassType.BERSERK) {

            double maxHealth = attacker.getAttribute(
                    Attribute.MAX_HEALTH
            ) != null
                    ? attacker.getAttribute(Attribute.MAX_HEALTH).getValue()
                    : 20.0;

            double healthRatio = attacker.getHealth() / maxHealth;

            double threshold = plugin.getConfig().getDouble(
                    "combat.berserk.low-health-percent", 0.25
            );

            if (healthRatio < threshold) {

                double maxBonus = plugin.getConfig().getDouble(
                        "combat.berserk.max-bonus-damage", 5
                );

                double bonus = maxBonus *
                        (1.0 - healthRatio / Math.max(threshold, 0.01));

                event.setDamage(event.getDamage() + bonus);
            }

            if (plugin.visualsEnabled()) {
                target.getWorld().spawnParticle(
                        Particle.FLAME,
                        target.getLocation().add(0, 1, 0),
                        8,
                        0.25, 0.35, 0.25,
                        0.01
                );
            }
        }

        // -------------------------
        // SHADOW
        // -------------------------

        if (type == ClassType.SHADOW && target instanceof LivingEntity victim) {

            Vector toAttacker = attacker.getLocation().toVector()
                    .subtract(victim.getLocation().toVector())
                    .normalize();

            Vector facing = victim.getLocation()
                    .getDirection()
                    .normalize();

            // Dot < -0.55 означает, что атакующий находится за спиной.
            if (facing.dot(toAttacker) < -0.55) {

                event.setDamage(
                        event.getDamage() +
                                plugin.getConfig().getDouble(
                                        "combat.shadow.backstab-bonus-damage", 3
                                )
                );

                fx(attacker, Particle.CRIT, 8);
            }
        }

        // -------------------------
        // THUNDER
        // -------------------------

        if (type == ClassType.THUNDER) {

            int count = hitCounts.merge(
                    attacker.getUniqueId(),
                    1,
                    Integer::sum
            );

            if (count % 3 == 0) {

                event.setDamage(
                        event.getDamage() +
                                plugin.getConfig().getDouble(
                                        "combat.thunder.third-hit-bonus-damage", 2
                                )
                );

                Location loc = target.getLocation();

                if (plugin.visualsEnabled()) {

                    loc.getWorld().strikeLightningEffect(loc);

                    loc.getWorld().spawnParticle(
                            Particle.ELECTRIC_SPARK,
                            loc.clone().add(0, 1, 0),
                            24,
                            0.5, 0.7, 0.5,
                            0.08
                    );

                    loc.getWorld().playSound(
                            loc,
                            Sound.ENTITY_LIGHTNING_BOLT_THUNDER,
                            0.35f,
                            1.7f
                    );
                }
            }
        }

        // -------------------------
        // RANGER
        // -------------------------

        if (type == ClassType.RANGER &&
                damager instanceof Projectile projectile &&
                target instanceof LivingEntity livingTarget) {

            Location origin = projectileOrigins.get(
                    projectile.getUniqueId()
            );

            if (origin != null &&
                    origin.getWorld().equals(livingTarget.getWorld())) {

                double distance = origin.distance(
                        livingTarget.getLocation()
                );

                double minimum = plugin.getConfig().getDouble(
                        "combat.ranger.long-shot-min-distance", 22
                );

                if (distance >= minimum) {

                    event.setDamage(
                            event.getDamage() +
                                    plugin.getConfig().getDouble(
                                            "combat.ranger.long-shot-bonus-damage", 3
                                    )
                    );

                    livingTarget.setGlowing(true);

                    int duration = plugin.getConfig().getInt(
                            "combat.ranger.mark-seconds", 4
                    ) * 20;

                    UUID targetId = livingTarget.getUniqueId();

                    Bukkit.getScheduler().runTaskLater(
                            plugin,
                            () -> {
                                Entity entity = Bukkit.getEntity(targetId);

                                if (entity instanceof LivingEntity living &&
                                        living.isValid() && !living.isDead()) {
                                    living.setGlowing(false);
                                }
                            },
                            duration
                    );

                    fx(attacker, Particle.HAPPY_VILLAGER, 12);
                    fx(livingTarget, Particle.HAPPY_VILLAGER, 12);
                }
            }
        }
    }

    // =====================================================
    // KILL PASSIVES
    // =====================================================

    @EventHandler
    public void onDeath(EntityDeathEvent event) {

        Player killer = event.getEntity().getKiller();

        if (killer == null)
            return;

        ClassType type = plugin.getStore().get(killer.getUniqueId());

        if (type == null)
            return;

        switch (type) {

            case BERSERK -> {

                killer.addPotionEffect(new PotionEffect(
                        PotionEffectType.SPEED,
                        plugin.getConfig().getInt(
                                "combat.berserk.kill-speed-seconds", 5
                        ) * 20,
                        plugin.getConfig().getInt(
                                "combat.berserk.kill-speed-amplifier", 1
                        ),
                        false, true, true
                ));

                fx(killer, Particle.FLAME, 24);
            }

            case SHADOW -> {

                killer.addPotionEffect(new PotionEffect(
                        PotionEffectType.INVISIBILITY,
                        plugin.getConfig().getInt(
                                "combat.shadow.kill-invisibility-seconds", 3
                        ) * 20,
                        0,
                        false, true, true
                ));

                fx(killer, Particle.SMOKE, 25);
            }

            case ALCHEMIST -> {

                PotionEffectType[] effects = {
                        PotionEffectType.SPEED,
                        PotionEffectType.STRENGTH,
                        PotionEffectType.REGENERATION,
                        PotionEffectType.JUMP_BOOST,
                        PotionEffectType.RESISTANCE
                };

                PotionEffectType chosen = effects[
                        ThreadLocalRandom.current().nextInt(effects.length)
                ];

                killer.addPotionEffect(new PotionEffect(
                        chosen,
                        plugin.getConfig().getInt(
                                "combat.alchemist.buff-seconds", 8
                        ) * 20,
                        0,
                        false, true, true
                ));

                fx(killer, Particle.WITCH, 30);

                killer.getWorld().playSound(
                        killer.getLocation(),
                        Sound.ENTITY_WITCH_DRINK,
                        0.7f,
                        1.4f
                );
            }

            case RANGER -> {

                fx(killer, Particle.HAPPY_VILLAGER, 24);

                killer.getWorld().playSound(
                        killer.getLocation(),
                        Sound.BLOCK_AZALEA_LEAVES_BREAK,
                        0.8f,
                        1.3f
                );
            }

            case RIFT -> {

                Location center = killer.getLocation().add(0, 1, 0);

                if (plugin.visualsEnabled()) {

                    killer.getWorld().spawnParticle(
                            Particle.PORTAL,
                            center,
                            65,
                            0.9, 0.7, 0.9,
                            0.35
                    );

                    killer.getWorld().spawnParticle(
                            Particle.REVERSE_PORTAL,
                            center,
                            25,
                            0.5, 0.5, 0.5,
                            0.1
                    );

                    killer.getWorld().playSound(
                            center,
                            Sound.BLOCK_RESPAWN_ANCHOR_CHARGE,
                            0.7f,
                            0.6f
                    );
                }
            }

            default -> {
            }
        }
    }

    // =====================================================
    // AMBIENT EFFECTS
    // =====================================================

    @EventHandler
    public void onMove(PlayerMoveEvent event) {

        if (event.getTo() == null)
            return;

        // Не запускаем эффекты при повороте головы.
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ())
            return;

        Player player = event.getPlayer();

        ClassType type = plugin.getStore().get(player.getUniqueId());

        if (type == null || !plugin.visualsEnabled())
            return;

        int period = Math.max(
                1,
                plugin.getConfig().getInt(
                        "visual.ambient-period-ticks", 12
                )
        );

        if (player.getTicksLived() % period != 0)
            return;

        // -------------------------
        // BERSERK AURA
        // -------------------------

        if (type == ClassType.BERSERK) {

            double maxHealth = player.getAttribute(
                    Attribute.MAX_HEALTH
            ) != null
                    ? player.getAttribute(Attribute.MAX_HEALTH).getValue()
                    : 20.0;

            double percent = player.getHealth() / maxHealth;

            if (percent <= plugin.getConfig().getDouble(
                    "combat.berserk.aura-health-percent", 0.45
            )) {

                player.getWorld().spawnParticle(
                        Particle.DAMAGE_INDICATOR,
                        player.getLocation().add(0, 1, 0),
                        5,
                        0.35, 0.5, 0.35,
                        0.02
                );
            }
        }

        // -------------------------
        // THUNDER RUNNING
        // -------------------------

        if (type == ClassType.THUNDER && player.isSprinting()) {

            player.getWorld().spawnParticle(
                    Particle.ELECTRIC_SPARK,
                    player.getLocation().add(0, 0.8, 0),
                    4,
                    0.35, 0.35, 0.35,
                    0.02
            );
        }

        // -------------------------
        // RIFT JUMP
        // -------------------------

        if (type == ClassType.RIFT &&
                !player.isOnGround() &&
                player.getVelocity().getY() > 0.12) {

            player.getWorld().spawnParticle(
                    Particle.PORTAL,
                    player.getLocation().subtract(0, 0.5, 0),
                    6,
                    0.2, 0.15, 0.2,
                    0.1
            );
        }

        // -------------------------
        // RANGER NATURE
        // -------------------------

        if (type == ClassType.RANGER &&
                isNatureBiome(player.getLocation().getBlock().getBiome())) {

            player.getWorld().spawnParticle(
                    Particle.COMPOSTER,
                    player.getLocation().add(0, 1, 0),
                    3,
                    0.4, 0.5, 0.4,
                    0.01
            );
        }
    }

    // =====================================================
    // BIOMES
    // =====================================================

    private boolean isNatureBiome(Biome biome) {

        String name = biome.name();

        return name.contains("FOREST")
                || name.contains("JUNGLE")
                || name.contains("TAIGA")
                || name.contains("MEADOW")
                || name.contains("GROVE")
                || name.contains("PLAINS");
    }

    // =====================================================
    // VISUAL EFFECTS
    // =====================================================

    private void fx(Player player, Particle particle, int amount) {

        if (!plugin.visualsEnabled())
            return;

        int limit = plugin.getConfig().getInt(
                "visual.particles-per-effect", 12
        );

        player.getWorld().spawnParticle(
                particle,
                player.getLocation().add(0, 1, 0),
                Math.min(amount, Math.max(0, limit) * 2),
                0.45, 0.6, 0.45,
                0.04
        );
    }
}
