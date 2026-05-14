package dev.nitka.nodewire.graph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * End-to-end evaluator checks: build a small graph, run [GraphEvaluator],
 * verify the values at terminal output pins. Stock evaluators come from
 * [StockNodeTypes.registerAll] which we run once per class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphEvaluatorTest {

    @BeforeAll
    fun setUp() = StockNodeTypes.registerAll()

    @Test
    fun andOfTwoBoolConstsTrue() {
        val a = StockNodeTypes.CONSTANT.newInstance().also { it.config.putBoolean("bool", true) }
        val b = StockNodeTypes.CONSTANT.newInstance().also { it.config.putBoolean("bool", true) }
        val and = StockNodeTypes.AND.newInstance()
        val g = NodeGraph().apply {
            add(a); add(b); add(and)
            addEdge(Edge(PinRef(a.id, "out"), PinRef(and.id, "a")))
            addEdge(Edge(PinRef(b.id, "out"), PinRef(and.id, "b")))
        }
        val r = GraphEvaluator.eval(g)
        assertEquals(PinValue.Bool(true), r.valueAt(and.id, "out"))
    }

    @Test
    fun andOfTrueAndFalseFalse() {
        val a = StockNodeTypes.CONSTANT.newInstance().also { it.config.putBoolean("bool", true) }
        val b = StockNodeTypes.CONSTANT.newInstance().also { it.config.putBoolean("bool", false) }
        val and = StockNodeTypes.AND.newInstance()
        val g = NodeGraph().apply {
            add(a); add(b); add(and)
            addEdge(Edge(PinRef(a.id, "out"), PinRef(and.id, "a")))
            addEdge(Edge(PinRef(b.id, "out"), PinRef(and.id, "b")))
        }
        assertEquals(PinValue.Bool(false), GraphEvaluator.eval(g).valueAt(and.id, "out"))
    }

    @Test
    fun addThreeIntsChained() {
        // (3 + 5) + 7 = 15
        val c3 = StockNodeTypes.CONSTANT.newInstance().also {
            it.config.putString("type", "INT"); it.config.putInt("int", 3)
        }
        val c5 = StockNodeTypes.CONSTANT.newInstance().also {
            it.config.putString("type", "INT"); it.config.putInt("int", 5)
        }
        val c7 = StockNodeTypes.CONSTANT.newInstance().also {
            it.config.putString("type", "INT"); it.config.putInt("int", 7)
        }
        val add1 = StockNodeTypes.ADD_INT.newInstance()
        val add2 = StockNodeTypes.ADD_INT.newInstance()
        val g = NodeGraph().apply {
            add(c3); add(c5); add(c7); add(add1); add(add2)
            addEdge(Edge(PinRef(c3.id, "out"), PinRef(add1.id, "a")))
            addEdge(Edge(PinRef(c5.id, "out"), PinRef(add1.id, "b")))
            addEdge(Edge(PinRef(add1.id, "out"), PinRef(add2.id, "a")))
            addEdge(Edge(PinRef(c7.id, "out"), PinRef(add2.id, "b")))
        }
        assertEquals(PinValue.Int(15), GraphEvaluator.eval(g).valueAt(add2.id, "out"))
    }

    @Test
    fun compareIntProducesAllThreeOutputs() {
        val a = StockNodeTypes.CONSTANT.newInstance().also {
            it.config.putString("type", "INT"); it.config.putInt("int", 5)
        }
        val b = StockNodeTypes.CONSTANT.newInstance().also {
            it.config.putString("type", "INT"); it.config.putInt("int", 3)
        }
        val cmp = StockNodeTypes.COMPARE_INT.newInstance()
        val g = NodeGraph().apply {
            add(a); add(b); add(cmp)
            addEdge(Edge(PinRef(a.id, "out"), PinRef(cmp.id, "a")))
            addEdge(Edge(PinRef(b.id, "out"), PinRef(cmp.id, "b")))
        }
        val r = GraphEvaluator.eval(g)
        assertEquals(PinValue.Bool(true), r.valueAt(cmp.id, "gt"))
        assertEquals(PinValue.Bool(false), r.valueAt(cmp.id, "eq"))
        assertEquals(PinValue.Bool(false), r.valueAt(cmp.id, "lt"))
    }

    @Test
    fun unconnectedInputDefaultsToZero() {
        // ADD_INT with only `a` connected → `b` defaults to 0, so result == a.
        val a = StockNodeTypes.CONSTANT.newInstance().also {
            it.config.putString("type", "INT"); it.config.putInt("int", 42)
        }
        val add = StockNodeTypes.ADD_INT.newInstance()
        val g = NodeGraph().apply {
            add(a); add(add)
            addEdge(Edge(PinRef(a.id, "out"), PinRef(add.id, "a")))
        }
        assertEquals(PinValue.Int(42), GraphEvaluator.eval(g).valueAt(add.id, "out"))
    }

    @Test
    fun externalOutputsAreInjectedAndOverrideEvaluator() {
        // A ChannelInput's "out" is overridden by the externally-supplied
        // value (simulates a tool-bound link from another logic block).
        val channelIn = StockNodeTypes.CHANNEL_INPUT.newInstance().also {
            it.config.putString("type", "BOOL")
        }
        val not = StockNodeTypes.NOT.newInstance()
        val g = NodeGraph().apply {
            add(channelIn); add(not)
            addEdge(Edge(PinRef(channelIn.id, "out"), PinRef(not.id, "in")))
        }
        val r = GraphEvaluator.eval(
            g,
            externalOutputs = mapOf((channelIn.id to "out") to PinValue.Bool(true)),
        )
        assertEquals(PinValue.Bool(false), r.valueAt(not.id, "out"))
    }
}
