package edu.wisc.maplens.mapparse

import java.util.PriorityQueue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class CellHeapTest {
    private data class Step(val cell: Int, val cost: Double)

    @Test
    fun `cells come out in the same order as java PriorityQueue, ties included`() {
        val random = Random(7)
        val heap = CellHeap()
        val queue = PriorityQueue<Step> { a, b -> a.cost.compareTo(b.cost) }
        val fromHeap = mutableListOf<Int>()
        val fromQueue = mutableListOf<Int>()
        var next = 0
        repeat(20_000) {
            // Mostly adds, with many equal costs, like the eight-neighbour path search.
            if (random.nextInt(3) != 0 || heap.size == 0) {
                val cost = random.nextInt(50) * 0.5
                heap.add(next, cost)
                queue += Step(next, cost)
                next++
            } else {
                assertEquals(queue.peek().cost, heap.peekCost())
                fromHeap += heap.poll()
                fromQueue += queue.remove().cell
            }
        }
        while (heap.isNotEmpty()) {
            fromHeap += heap.poll()
            fromQueue += queue.remove().cell
        }
        assertEquals(fromQueue, fromHeap)
    }
}
