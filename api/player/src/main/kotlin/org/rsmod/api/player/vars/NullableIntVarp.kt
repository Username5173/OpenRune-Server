package org.rsmod.api.player.vars

import kotlin.reflect.KProperty
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.game.entity.Player

public fun nullableIntVarp(varp: String): VariableNullableIntDelegate =
    VariableNullableIntDelegate(varp)

public class VariableNullableIntDelegate(private val varp: String) {
    init {
        require(varp.startsWith("varp.")) { "A nullable integer requires a whole varp: $varp" }
    }

    public operator fun getValue(thisRef: Player, property: KProperty<*>): Int? =
        if (varp in thisRef.vars) thisRef.vars[varp] else null

    public operator fun setValue(thisRef: Player, property: KProperty<*>, value: Int?) {
        if (value == null) VarPlayerIntMapSetter.clear(thisRef, varp)
        else VarPlayerIntMapSetter.set(thisRef, varp, value)
    }

    public operator fun getValue(thisRef: ProtectedAccess, property: KProperty<*>): Int? =
        getValue(thisRef.player, property)

    public operator fun setValue(thisRef: ProtectedAccess, property: KProperty<*>, value: Int?) {
        setValue(thisRef.player, property, value)
    }
}
