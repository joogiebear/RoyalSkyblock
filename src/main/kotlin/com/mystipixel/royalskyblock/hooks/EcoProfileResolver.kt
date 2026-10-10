package com.mystipixel.royalskyblock.hooks

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin
import com.willfp.eco.core.Eco
import org.bukkit.OfflinePlayer
import java.lang.reflect.Proxy
import java.util.UUID

/**
 * Points eco at the player's active profile, so no data is copied on a switch (unlike
 * [EcoProfileBridge], which copies every registered key twice per switch). Released eco lacks this
 * capability, so it is looked up at runtime: without it [install] returns false and the bridge keeps
 * copying. Consulted on every data access, so it must stay cheap.
 */
object EcoProfileResolver {

    private const val RESOLVER_CLASS = "com.willfp.eco.core.data.PlayerProfileResolver"

    /**
     * Install the resolver.
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

    // The UUID a player's data belongs to: their active profile's shadow, or their own when no profile
    // can be determined (startup, or never picked one).
    private fun resolve(plugin: RoyalSkyblockPlugin, player: OfflinePlayer): UUID {
        val profiles = plugin.profilesOrNull() ?: return player.uniqueId
        // offline players are looked up without caching: see ProfileManager.peekActiveProfileId
        val active = (if (player.isOnline) profiles.getActiveProfileId(player.uniqueId)
            else profiles.peekActiveProfileId(player.uniqueId)) ?: return player.uniqueId
        return EcoProfileBridge.shadowUuid(player.uniqueId, active)
    }
}
