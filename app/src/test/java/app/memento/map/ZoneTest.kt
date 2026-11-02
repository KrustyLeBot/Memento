package app.memento.map

import app.memento.Zone3dPolicy
import app.memento.record.StartZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Synthetic places only: the repository is public. 45.5°N 6.5°E is a round point in the Alps.
class ZoneTest {
    private val lon = 6.5
    private val lat = 45.5
    private val mb = 1024L * 1024
    private val created = 1_790_000_000_000L

    private fun spec(s2: Int = 40, s3: Int = 40) = ZoneSpec(lon, lat, s2, s3)

    @Test
    fun printEstimates() {
        for (km in listOf(10, 20, 40, 60, 80)) {
            val p2 = TilePlans.forZone(lon, lat, km)
            val p3 = TilePlans.forZone3d(lon, lat, km, km)
            println("zone $km km: 2D ${p2.tileCount} tiles ${p2.estimatedBytes / 1_000_000} MB z${p2.orthoZooms.last}; " +
                "3D ${p3.tileCount} tiles ${p3.estimatedBytes / 1_000_000} MB (dem ${p3.demRanges.sumOf { it.count }}, z16 ${p3.corridorCount}), " +
                "extra ${TilePlans.zone3dExtraBytes(lon, lat, km, km) / 1_000_000} MB")
        }
    }

    @Test
    fun default2dZoneIs40kmAndAbout45Mb() {
        val p = TilePlans.forZone(lon, lat)
        assertEquals(40_000.0, p.square.widthM, 1.0)
        assertEquals(40_000.0, p.square.heightM, 1.0)
        assertEquals(8..15, p.orthoZooms)
        assertNull(p.demZooms)
        assertNull(p.corridor)
        assertTrue("${p.tileCount}", p.tileCount in 2_600..3_300)
        assertTrue("${p.estimatedBytes}", p.estimatedBytes in 38 * mb..50 * mb)
    }

    @Test
    fun default3dZoneAddsReliefAndAZ16DiscOfAbout5km() {
        val p2 = TilePlans.forZone(lon, lat)
        val p3 = TilePlans.forZone3d(lon, lat)
        assertEquals(p2.square, p3.square)
        assertEquals(p2.orthoRanges, p3.orthoRanges)
        assertEquals(TilePlans.DEM_ZOOMS, p3.demZooms)
        val disc = p3.corridor!!
        assertEquals(16..16, disc.zooms)
        assertEquals(5_000.0, disc.bufferM, 0.0)
        // a 5 km disc is ~78.5 km², a z16 tile at 45.5° ~0.18 km²: ~430 tiles plus the rim
        assertTrue("${disc.tileCount}", disc.tileCount in 420..600)
        val dem = p3.demRanges.sumOf { it.count }
        assertTrue("$dem", dem in 200..320)
        val extra = TilePlans.zone3dExtraBytes(lon, lat)
        assertEquals(dem * TilePlans.DEM_TILE_BYTES + disc.tileCount * TilePlans.ORTHO_TILE_BYTES, extra)
        assertTrue("$extra", extra in 15 * mb..30 * mb)
    }

    @Test
    fun a3dZoneLargerThanThe2dOneBringsItsOwnPhoto() {
        val p = TilePlans.forZone3d(lon, lat, 20, 60)
        assertEquals(60_000.0, p.square.widthM, 1.0)
        assertEquals(p.square, p.demSquare)
        assertEquals(TilePlans.forZone(lon, lat, 60).orthoRanges, p.orthoRanges)
    }

    @Test
    fun a3dZoneSmallerThanThe2dOneKeepsItsReliefInside() {
        val p = TilePlans.forZone3d(lon, lat, 40, 10)
        assertEquals(40_000.0, p.square.widthM, 1.0)
        assertEquals(10_000.0, p.demSquare!!.widthM, 1.0)
        assertTrue(p.demRanges.sumOf { it.count } < TilePlans.forZone3d(lon, lat, 40, 40).demRanges.sumOf { it.count })
        // the disc never reaches past the relief: a 5 km square gets a 2.5 km disc
        assertEquals(2_500.0, TilePlans.forZone3d(lon, lat, 40, 5).corridor!!.bufferM, 0.0)
    }

    @Test
    fun largestZonesStayWithinTheBudgetAtFullDefinition() {
        val p2 = TilePlans.forZone(lon, lat, 80)
        assertEquals(15, p2.orthoZooms.last)
        assertTrue("${p2.estimatedBytes}", p2.estimatedBytes in 140 * mb..TilePlans.ZONE_BUDGET)
        val p3 = TilePlans.forZone3d(lon, lat, 80, 80)
        assertEquals(15, p3.orthoZooms.last)
        assertNotNull(p3.corridor)
        assertTrue("${p3.estimatedBytes}", p3.estimatedBytes <= TilePlans.ZONE_BUDGET)
    }

    @Test
    fun estimatesGrowWithTheSide() {
        val sizes = TilePlans.ZONE_2D_KM.step(TilePlans.ZONE_KM_STEP).map { TilePlans.forZone(lon, lat, it).estimatedBytes }
        assertEquals(sizes.sorted(), sizes)
        assertTrue(TilePlans.zone3dExtraBytes(lon, lat, 40, 20) < TilePlans.zone3dExtraBytes(lon, lat, 40, 40))
    }

    @Test
    fun slidersSnapToTheirRangeAndStep() {
        assertEquals(40, TilePlans.snapKm(41, TilePlans.ZONE_2D_KM))
        assertEquals(10, TilePlans.snapKm(5, TilePlans.ZONE_2D_KM))
        assertEquals(5, TilePlans.snapKm(5, TilePlans.ZONE_3D_KM))
        assertEquals(80, TilePlans.snapKm(200, TilePlans.ZONE_3D_KM))
    }

    private fun zonePack(id: String, with3d: Boolean, s2: Int = 40, s3: Int = 40, complete: Boolean = true): Pack {
        val p = spec(s2, s3).plan(with3d)
        return Pack(
            id, Zones.name(with3d), created, p.square, p.orthoZooms.last, if (with3d) Zones.KINDS_3D else setOf(Pack.KIND_ORTHO),
            0, complete, hasRoute = false, corridor = p.corridor, relief = p.demSquare,
        )
    }

    @Test
    fun a3dZoneCovers3dInsideItsReliefOnly() {
        val p = zonePack("z-1", true, 40, 10)
        val now = created + 1000
        assertTrue(Pack.covers3d(listOf(p), lon, lat, now))
        // 15 km east: inside the photo, outside the relief
        val east = lon + 15_000 / (MapSquare.M_PER_DEG_LON * Math.cos(Math.toRadians(lat)))
        assertTrue(p.square.contains(east, lat))
        assertFalse(Pack.covers3d(listOf(p), east, lat, now))
        assertFalse(Pack.covers3d(listOf(zonePack("z-2", false)), lon, lat, now))
        assertFalse(Pack.covers3d(listOf(p.copy(complete = false)), lon, lat, now))
    }

    @Test
    fun a3dZoneManifestRoundTripsWithItsDiscAndRelief() {
        val p = zonePack("z-1", true, 60, 20)
        val back = Pack.parse(p.toJson())
        // the manifest rounds the boxes to 1e-7° (1 cm): the same tiles, and stable from then on
        assertEquals(back, Pack.parse(back.toJson()))
        assertEquals(p.corridor, back.corridor)
        assertEquals(p.plan().tileCount, back.plan().tileCount)
        assertEquals(p.relief!!.widthM, back.relief!!.widthM, 0.1)
    }

    @Test
    fun a2dZonePackIsUpgradedInPlace() {
        val z = spec()
        val two = zonePack("z-1", false)
        val now = created + 1000
        assertSame(two, Zones.reusable(listOf(two), z, true, now))
        assertTrue(Zones.needsUpgrade(two, z, true))
        assertFalse(Zones.needsUpgrade(two, z, false))
        val up = Zones.upgrade(two, z)
        assertEquals("z-1", up.id)
        assertEquals(created, up.created)
        assertTrue(up.has3d)
        assertFalse(up.complete)
        assertEquals(TilePlans.forZone3d(lon, lat).tileCount, up.plan().tileCount)
        assertFalse(Zones.needsUpgrade(up, z, true))
    }

    @Test
    fun aRoutePackIsNeverUpgradedAndAnExpiredPackNeverReused() {
        val z = spec()
        val route = zonePack("r-1", false).copy(hasRoute = true)
        assertNull(Zones.reusable(listOf(route), z, true, created + 1000))
        assertSame(route, Zones.reusable(listOf(route), z, false, created + 1000))
        assertNull(Zones.reusable(listOf(zonePack("z-1", false)), z, false, created + Pack.LIFETIME_MS))
    }

    @Test
    fun theQuestionFollowsTheSetting() {
        val z = spec()
        assertEquals(StartZone.Answer.PENDING, StartZone.of(z, Zone3dPolicy.ASK).answer)
        assertEquals(StartZone.Answer.YES, StartZone.of(z, Zone3dPolicy.ALWAYS).answer)
        assertEquals(StartZone.Answer.NO, StartZone.of(z, Zone3dPolicy.NEVER).answer)
        // already in 3D here: nothing to ask
        assertEquals(StartZone.Answer.YES, StartZone.of(z, Zone3dPolicy.ASK, already3d = true).answer)
        assertEquals(TilePlans.zone3dExtraBytes(lon, lat), StartZone.of(z, Zone3dPolicy.ASK).extraBytes)
    }

    @Test
    fun the2dZoneIsAskedFirstAndThe3dOnlyAfterAYes() {
        val pending = StartZone.of(spec(), Zone3dPolicy.ASK)
        assertEquals(false, pending.next())
        val asked = pending.requested(false)
        assertNull(asked.next())
        val yes = asked.copy(answer = StartZone.Answer.YES)
        assertEquals(true, yes.next())
        assertNull(yes.requested(true).next())
        assertNull(asked.copy(answer = StartZone.Answer.NO).next())
        // "Toujours": straight to the 3D pack, which holds the 2D photo
        val always = StartZone.of(spec(), Zone3dPolicy.ALWAYS)
        assertEquals(true, always.next())
        assertNull(always.requested(true).next())
    }
}
