package edu.wisc.maplens.guidance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CueAnnouncerTest {
    private fun turn(n: Int) = Instruction(Maneuver.TURN_RIGHT, n, "at the junction")

    private fun arrive(n: Int) = Instruction(Maneuver.ARRIVE, n, "at room 3546")

    /** Walks a route: right turn after 20 steps, then the door 6 steps later. */
    private fun walk(announcer: CueAnnouncer): List<Announcement?> =
        (20 downTo 1).map { announcer.next(turn(it), 0) } +
            (6 downTo 0).map { announcer.next(arrive(it), 1) }

    @Test
    fun `one heads-up, one now with a vibration and one arrival per maneuver`() {
        val said = walk(CueAnnouncer()).filterNotNull()
        assertEquals(
            listOf(
                Announcement("In 20 steps, turn right at the junction", null),
                Announcement("Turn right at the junction", Haptic.TURN),
                Announcement("In 6 steps, arrive at room 3546", null),
                Announcement("You have arrived at room 3546", Haptic.ARRIVE),
            ),
            said,
        )
    }

    @Test
    fun `a far maneuver gets its heads-up once it comes within range`() {
        val a = CueAnnouncer()
        // The first instruction of a route is always spoken, however far.
        assertEquals("In 40 steps, turn right at the junction", a.next(turn(40), 0)?.text)
        assertNull(a.next(turn(30), 0))
        // A later maneuver (new cue index) stays quiet until it is 12 steps away.
        val left = { n: Int -> Instruction(Maneuver.TURN_LEFT, n, "at the junction") }
        assertNull(a.next(left(30), 1))
        assertNull(a.next(left(13), 1))
        assertEquals("In 12 steps, turn left at the junction", a.next(left(12), 1)?.text)
        assertNull(a.next(left(11), 1))
    }

    @Test
    fun `arriving with no maneuvers left skips the now cue but still vibrates on arrival`() {
        val a = CueAnnouncer()
        assertEquals("In 3 steps, arrive at room 3546", a.next(arrive(3), 0)?.text)
        assertNull(a.next(arrive(2), 0))
        assertNull(a.next(arrive(1), 0))
        assertEquals(Haptic.ARRIVE, a.next(arrive(0), 0)?.haptic)
        assertNull(a.next(arrive(0), 0))
    }

    @Test
    fun `reset starts a new route`() {
        val a = CueAnnouncer()
        walk(a)
        a.reset()
        assertEquals(4, walk(a).filterNotNull().size)
    }

    @Test
    fun `wording`() {
        val bear = Instruction(Maneuver.SLIGHT_LEFT, 1, "")
        assertEquals("In 1 step, bear left", CueAnnouncer.prepareText(bear))
        assertEquals("Turn around", CueAnnouncer.nowText(Instruction(Maneuver.U_TURN, 1, "")))
        val door = arrive(0).copy(detail = "at your destination")
        assertEquals("You have arrived at your destination", CueAnnouncer.arrivalText(door))
    }
}
