package com.mystipixel.royalskyblock.libreforge

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin
import com.willfp.libreforge.ProvidedHolder
import com.willfp.libreforge.SimpleProvidedHolder
import com.willfp.libreforge.ViolationContext
import com.willfp.libreforge.registerSpecificHolderProvider
import com.willfp.libreforge.toDispatcher
import com.willfp.libreforge.updateHolders
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File

/**
 * Wires RoyalSkyblock's perks and island upgrades into libreforge as effect holders.
 *
 * A perk or a purchased upgrade tier can now carry any libreforge effect chain, so island content is
 * written in the same dialect as the rest of the eco suite instead of being limited to the handful of
 * behaviours this plugin implements natively.
 *
 * ## What is active for whom
 *
 * - **Upgrades** apply to *anyone standing on the island*, members and visitors alike — the island
 *   itself is upgraded, so the buff belongs to the place. Only the **current** tier's chain is
 *   applied, not every tier below it, matching how the existing `value:` field already works ("the
 *   total at that tier", not an increment).
 * - **Perks** apply only on your *own* island, which is the behaviour perks already had.
 *
 * ## Refreshing
 *
 * libreforge caches a dispatcher's holders, so anything that changes the answer has to invalidate
 * them: crossing a world boundary (handled by [RoyalHolderListener]), buying an upgrade, or an island
 * levelling past a perk's requirement. [refresh] is the entry point the Java side calls.
 */
object RoyalHolders {

    private var perkHolders: Map<String, RoyalHolder> = emptyMap()

    /** track key -> tier number -> holder. */
    private var upgradeHolders: Map<String, Map<Int, RoyalHolder>> = emptyMap()

    /** Register the provider once, during enable. */
    fun register(plugin: RoyalSkyblockPlugin) {
        reload(plugin)
        registerSpecificHolderProvider<Player> { player -> holdersFor(plugin, player) }
    }

    /** Recompile every chain from disk. Safe to call repeatedly; called on reload. */
    fun reload(plugin: RoyalSkyblockPlugin) {
        perkHolders = compileSection(plugin, "perks.yml", "perks", "perk")
        upgradeHolders = compileUpgrades(plugin)
        refreshAll()
    }

    /**
     * Compile a `<file>/<root>/<id>/effects` block per entry — the perks layout.
     */
    private fun compileSection(
        plugin: RoyalSkyblockPlugin,
        fileName: String,
        root: String,
        label: String
    ): Map<String, RoyalHolder> {
        val section = load(plugin, fileName)?.getConfigurationSection(root) ?: return emptyMap()
        val out = mutableMapOf<String, RoyalHolder>()
        for (key in section.getKeys(false)) {
            val entry = section.getConfigurationSection(key) ?: continue
            val context = ViolationContext(plugin, "$label $key")
            val id = NamespacedKey(plugin, "${label}_${key.lowercase()}")
            compileRoyalHolder(plugin, id, entry, context)?.let { out[key] = it }
        }
        return out
    }

    /**
     * Upgrades nest one level deeper than perks: the chain lives on an individual tier, so a track can
     * grant a different effect at each tier.
     */
    private fun compileUpgrades(plugin: RoyalSkyblockPlugin): Map<String, Map<Int, RoyalHolder>> {
        val root = load(plugin, "upgrades.yml") ?: return emptyMap()
        val out = mutableMapOf<String, Map<Int, RoyalHolder>>()
        for (track in root.getKeys(false)) {
            val tiers = root.getConfigurationSection("$track.tiers") ?: continue
            val perTier = mutableMapOf<Int, RoyalHolder>()
            for (tierKey in tiers.getKeys(false)) {
                val tierNumber = tierKey.toIntOrNull() ?: continue
                val tier = tiers.getConfigurationSection(tierKey) ?: continue
                val context = ViolationContext(plugin, "upgrade $track tier $tierNumber")
                val id = NamespacedKey(plugin, "upgrade_${track.lowercase()}_$tierNumber")
                compileRoyalHolder(plugin, id, tier, context)?.let { perTier[tierNumber] = it }
            }
            if (perTier.isNotEmpty()) {
                out[track] = perTier
            }
        }
        return out
    }

    private fun load(plugin: RoyalSkyblockPlugin, fileName: String): YamlConfiguration? {
        val file = File(plugin.dataFolder, fileName)
        return if (file.exists()) YamlConfiguration.loadConfiguration(file) else null
    }

    private fun holdersFor(plugin: RoyalSkyblockPlugin, player: Player): Collection<ProvidedHolder> {
        // Not on an island at all (hub, a normal world) — nothing applies.
        val island = plugin.islands().getIslandByWorld(player.world) ?: return emptyList()
        val holders = mutableListOf<ProvidedHolder>()

        if (upgradeHolders.isNotEmpty()) {
            for (def in plugin.upgrades().all()) {
                val tier = island.upgradeTier(def.key())
                if (tier <= 0) {
                    continue
                }
                upgradeHolders[def.key()]?.get(tier)?.let { holders += SimpleProvidedHolder(it) }
            }
        }

        if (plugin.perks().enabled() && perkHolders.isNotEmpty() && isOwnIsland(plugin, player, island.id())) {
            val level = island.level().toInt()
            for (perk in plugin.perks().perks()) {
                if (perk.requiredLevel() > level) {
                    continue
                }
                perkHolders[perk.id]?.let { holders += SimpleProvidedHolder(it) }
            }
        }

        return holders
    }

    private fun isOwnIsland(plugin: RoyalSkyblockPlugin, player: Player, islandId: java.util.UUID): Boolean {
        val activeProfile = plugin.profiles().getActiveProfileId(player.uniqueId) ?: return false
        return plugin.islands().getIslandByProfile(activeProfile)?.id() == islandId
    }

    /** Invalidate one player's cached holders — call after anything that changes what applies to them. */
    @JvmStatic
    fun refresh(player: Player) {
        player.toDispatcher().updateHolders()
    }

    /** Invalidate everyone. Used after a reload recompiles the chains. */
    @JvmStatic
    fun refreshAll() {
        for (player in Bukkit.getOnlinePlayers()) {
            player.toDispatcher().updateHolders()
        }
    }
}
