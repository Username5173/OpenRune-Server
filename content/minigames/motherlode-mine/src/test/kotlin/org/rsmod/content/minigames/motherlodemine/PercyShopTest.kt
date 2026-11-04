package org.rsmod.content.minigames.motherlodemine

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.ItemServerType
import dev.openrune.types.aconverted.interf.IfButtonOp
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import net.rsprot.protocol.common.game.outgoing.inv.InventoryObject
import net.rsprot.protocol.game.outgoing.inv.UpdateInvFull
import net.rsprot.protocol.game.outgoing.misc.player.MessageGame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.api.market.MarketPrices
import org.rsmod.api.player.interact.NpcInteractions
import org.rsmod.api.player.output.UpdateInventory
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.shops.Shops
import org.rsmod.api.shops.operation.ShopOperationMap
import org.rsmod.api.shops.restock.ShopRestockProcess
import org.rsmod.api.shops.restock.ShopRestockScript
import org.rsmod.content.minigames.motherlodemine.configs.MotherlodeConstants
import org.rsmod.content.minigames.motherlodemine.scripts.PercyScript
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.shop.Shop
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

@OptIn(InternalApi::class)
@ResourceLock("ServerCacheManager")
class PercyShopTest {
    @Test
    fun `both shoppers see purchased stock decrease before it restocks on the following tick`() {
        val f = Fixture()
        val buyer = f.player()
        val observer = f.player()
        val shop = f.trade(buyer)
        val otherShop = f.trade(observer)
        assertSame(shop.inv, otherShop.inv)
        assertEquals(
            MotherlodeConstants.SHOP_TEMPLATE.asRSCM(RSCMType.INV),
            shop.inv.type.id,
        )
        assertEquals(8, shop.inv.size)
        assertEquals(MotherlodeConstants.CURRENCY_NUGGET, shop.currency)
        assertEquals(100, shop.inv.count(MotherlodeConstants.OBJ_PROSPECTOR_HAT))

        f.startCycle()
        f.buyHat(buyer, shop)
        assertEquals(1, buyer.inv.count(MotherlodeConstants.OBJ_PROSPECTOR_HAT))
        assertEquals(60, buyer.inv.count(MotherlodeConstants.OBJ_NUGGET))
        for (player in listOf(buyer, observer)) f.assertStockPacket(player, 99)
        f.finishCycle()

        f.startCycle()
        f.finishCycle()
        for (player in listOf(buyer, observer)) f.assertStockPacket(player, 100)
        assertTrue(f.restock.modifiedShops.isEmpty())
    }

    @Test
    fun `last item leaves an empty stock row and cannot be bought twice before restocking`() {
        val f = Fixture()
        val buyer = f.player()
        val shop = f.trade(buyer)
        val slot = f.hatSlot(shop)
        shop.inv[slot] = shop.inv[slot]!!.copy(count = 1)

        f.startCycle()
        f.buyHat(buyer, shop)
        f.buyHat(buyer, shop)
        assertEquals(1, buyer.inv.count(MotherlodeConstants.OBJ_PROSPECTOR_HAT))
        assertEquals(60, buyer.inv.count(MotherlodeConstants.OBJ_NUGGET))
        f.assertStockPacket(buyer, 0)
        f.finishCycle()
        f.assertStockPacket(buyer, 1)
    }

    @Test
    fun `prospector jacket sells back for 80 percent and restocks`() {
        val f = Fixture()
        val seller = f.player()
        val shop = f.trade(seller)
        val slot = seller.inv.indices.first { seller.inv[it] == null }
        seller.inv[slot] = InvObj(MotherlodeConstants.OBJ_PROSPECTOR_TOP, 1)

        f.startCycle()
        f.sell(seller, shop, slot)

        assertEquals(0, seller.inv.count(MotherlodeConstants.OBJ_PROSPECTOR_TOP))
        assertEquals(148, seller.inv.count(MotherlodeConstants.OBJ_NUGGET))
        assertEquals(101, shop.inv.count(MotherlodeConstants.OBJ_PROSPECTOR_TOP))
        f.finishCycle()

        assertEquals(100, shop.inv.count(MotherlodeConstants.OBJ_PROSPECTOR_TOP))
    }

    @Test
    fun `every nugget shop stock item can be sold back for 80 percent`() {
        val refunds =
            mapOf(
                MotherlodeConstants.OBJ_PROSPECTOR_HAT to 32,
                MotherlodeConstants.OBJ_PROSPECTOR_TOP to 48,
                MotherlodeConstants.OBJ_PROSPECTOR_LEGS to 40,
                MotherlodeConstants.OBJ_PROSPECTOR_BOOTS to 24,
                MotherlodeConstants.OBJ_COAL_BAG to 80,
                MotherlodeConstants.OBJ_GEM_BAG to 80,
                MotherlodeConstants.OBJ_SOFTCLAY_PACK to 8,
                MotherlodeConstants.OBJ_BAG_FULL_OF_GEMS to 32,
            )
        for ((obj, refund) in refunds) {
            val f = Fixture()
            val seller = f.player()
            val shop = f.trade(seller)
            val slot = seller.inv.indices.first { seller.inv[it] == null }
            seller.inv[slot] = InvObj(obj, 1)
            val stockBefore = shop.inv.count(obj)

            f.startCycle()
            f.sell(seller, shop, slot)

            assertEquals(0, seller.inv.count(obj)) { "Seller should lose $obj" }
            assertEquals(100 + refund, seller.inv.count(MotherlodeConstants.OBJ_NUGGET)) {
                "Refund for $obj"
            }
            assertEquals(stockBefore + 1, shop.inv.count(obj)) { "Shop stock for $obj" }
        }
    }

    @Test
    fun `selling multiple pieces is capped by the amount held and pays per piece`() {
        val f = Fixture()
        val seller = f.player()
        val shop = f.trade(seller)
        val slots = seller.inv.indices.filter { seller.inv[it] == null }.take(2)
        slots.forEach { seller.inv[it] = InvObj(MotherlodeConstants.OBJ_PROSPECTOR_TOP, 1) }

        f.startCycle()
        f.sell(seller, shop, slots.first(), IfButtonOp.Op4)

        assertEquals(0, seller.inv.count(MotherlodeConstants.OBJ_PROSPECTOR_TOP))
        assertEquals(196, seller.inv.count(MotherlodeConstants.OBJ_NUGGET))
        assertEquals(102, shop.inv.count(MotherlodeConstants.OBJ_PROSPECTOR_TOP))
    }

    @Test
    fun `volcanic prospector pieces are refused with percy's message`() {
        val f = Fixture()
        val seller = f.player()
        val shop = f.trade(seller)
        val slot = seller.inv.indices.first { seller.inv[it] == null }
        seller.inv[slot] = InvObj("obj.fossil_motherlode_reward_top", 1)

        f.startCycle()
        f.sell(seller, shop, slot)

        assertEquals(1, seller.inv.count("obj.fossil_motherlode_reward_top"))
        assertEquals(100, seller.inv.count(MotherlodeConstants.OBJ_NUGGET))
        assertEquals(100, shop.inv.count(MotherlodeConstants.OBJ_PROSPECTOR_TOP))
        assertTrue(
            f.messages(seller).contains(
                "You can't sell this item back to Percy as you did not buy it from this shop"
            )
        )
    }

    private class Fixture {
        private val events = EventBus()
        private val interactions = NpcInteractions(events)
        private val operations = ShopOperationMap()
        private val clock = MapClock(0)
        val restock = ShopRestockProcess(clock)

        init {
            operations.registerCurrenciesFromDb(restock, object : MarketPrices {
                override fun get(type: ItemServerType): Int? = null
            })
            val context = ScriptContext(events, CheatCommandMap(), EngineQueueCache())
            with(PercyScript(Shops(events), operations)) { context.startup() }
            with(ShopRestockScript(restock)) { context.startup() }
        }

        fun player() = Player().apply {
            client = RecordingClient()
            coords = CoordGrid(3756, 5668)
            currentMapClock = 100
            processedMapClock = 100
            inv = Inventory.create("inv.inv")
            inv[0] = InvObj(MotherlodeConstants.OBJ_NUGGET, 100)
        }

        fun trade(player: Player): Shop {
            val npc = Npc(MotherlodeConstants.NPC_PERCY, player.coords.translateZ(1))
            val event = checkNotNull(interactions.opTrigger(player, npc, InteractionOp.Op3))
            val coroutine = GameCoroutine("percy-shop-test")
            player.activeCoroutine = coroutine
            val context = ProtectedAccessContextFactory.empty().copy(
                getEventBus = { events }, getNpcInteractions = { interactions },
            )
            val access = ProtectedAccess(player, coroutine, context)
            var completion: Result<Unit>? = null
            val block: suspend () -> Unit = { assertTrue(events.publish(access, event)) }
            block.startCoroutine(object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) { completion = result }
            })
            checkNotNull(completion).getOrThrow()
            return checkNotNull(player.openedShop)
        }

        fun hatSlot(shop: Shop) = shop.inv.indexOfFirst { it?.id == MotherlodeConstants.OBJ_PROSPECTOR_HAT.asRSCM() }

        fun buyHat(player: Player, shop: Shop) {
            checkNotNull(operations[shop.currency]).shopInvOp(player, player.inv, shop, hatSlot(shop), IfButtonOp.Op2)
        }

        fun sell(player: Player, shop: Shop, slot: Int, op: IfButtonOp = IfButtonOp.Op2) {
            checkNotNull(operations[shop.currency]).sideInvOp(player, player.inv, shop, slot, op)
        }

        fun messages(player: Player): List<String> =
            (player.client as RecordingClient).messages.filterIsInstance<MessageGame>().map { it.message }

        fun startCycle() { events.publish(GameLifecycle.StartCycle) }

        fun finishCycle() {
            clock.tick()
            events.publish(GameLifecycle.LateCycle)
        }

        fun assertStockPacket(player: Player, count: Int) {
            val shop = checkNotNull(player.openedShop)
            UpdateInventory.updateInvFull(player, shop.inv)
            val packet = (player.client as RecordingClient).messages.filterIsInstance<UpdateInvFull>().last()
            assertEquals(InventoryObject(hatSlot(shop), MotherlodeConstants.OBJ_PROSPECTOR_HAT.asRSCM(), count), packet.getObject(hatSlot(shop)))
        }
    }

    private class RecordingClient : Client<Any, Any> {
        val messages = mutableListOf<Any>()
        override fun write(message: Any) { messages += message }
        override fun close() {}
        override fun read(player: Player) {}
        override fun flush() {}
        override fun flushHighPriority() {}
        override fun unregister(service: Any, player: Player) {}
    }

    companion object {
        @JvmStatic @BeforeAll
        fun cache() {
            ServerCacheManager.init(241).close()
            with(InvTransactionsScript(PlayerItemStorage(emptySet()))) {
                ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache()).startup()
            }
        }
    }
}
