package com.mystipixel.royalskyblock.hooks

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin
import com.willfp.eco.core.Eco
import org.bukkit.OfflinePlayer
import java.lang.reflect.Proxy
import java.util.UUID

/**
 * Points eco at the player's active profile, so no data has to be copied on a switch.
 *
 * [EcoProfileBridge] makes profiles work by copying every registered key between the player's live
 * data and a per-profile shadow. It is correct and it does not scale: the copy is every key the whole
 * suite has registered, of which almost none hold a value, and it happens twice per switch.
 *
 * If eco can be told which UUID a player's data belongs to, none of that is necessary — eco reads and
 * writes the profile directly and a profile becomes just another entry in its store. That capability
 * does not exist in released eco, so it is looked up at runtime rather than compiled against: on a
 * server without it [install] reports false and the bridge keeps copying.
 *
 * The resolver is consulted on every data access, so it must stay cheap. Active profile ids are held
 * in memory for online players, which is the only case that matters here.
 */
object EcoProfileResolver {

    private const val RESOLVER_CLASS = "com.willfp.eco.core.data.PlayerProfileResolver"

    /**
     * Install the resolver, returning whether eco accepted it.
     *
     * @return false if this eco has no resolver support, in which case nothing was changed
     */
    @JvmStatic
    fun install(plugin: RoyalSkyblockPlugin): Boolean {
        val eco = Eco.get()
        val resolverClass = runCatching {
            Class.forName(RESOLVER_CLASS, false, eco.javaClass.classLoader)
        }.getOrNull() ?: return false

        val setter = runCatching {
            eco.javaClass.getMethod("setPlayerProfileResolver", resolverClass)
        }.getOrNull() ?: return false

        val resolver = Proxy.newProxyInstance(resolverClass.classLoader, arrayOf(resolverClass)) { proxy, method, args ->
            when (method.name) {
                "resolve" -> resolve(plugin, args[0] as OfflinePlayer)
                "equals" -> proxy === args[0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "RoyalSkyblock profile resolver"
                else -> throw UnsupportedOperationException(method.name)
            }
        }

        return runCatching { setter.invoke(eco, resolver) }.isSuccess
    }

    /**
     * The UUID a player's data belongs to: their active profile's shadow, or their own.
     *
     * Falls back to the player's own UUID whenever a profile cannot be determined — before the
     * profile manager exists during startup, or for someone who has never picked one. Anything else
     * would send data somewhere it could not be read back from.
     */
    private fun resolve(plugin: RoyalSkyblockPlugin, player: OfflinePlayer): UUID {
        val profiles = plugin.profilesOrNull() ?: return player.uniqueId
        val active = profiles.getActiveProfileId(player.uniqueId) ?: return player.uniqueId
        return EcoProfileBridge.shadowUuid(player.uniqueId, active)
    }
}
