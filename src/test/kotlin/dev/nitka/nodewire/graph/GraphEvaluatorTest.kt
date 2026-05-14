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
        val and = StockNodeTypes.LOGIC_GATE.newInstance() // default op=AND
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
        val and = StockNodeTypes.LOGIC_GATE.newInstance() // default op=AND
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
        val add1 = StockNodeTypes.MATH.newInstance() // default op=ADD, type=INT
        val add2 = StockNodeTypes.MATH.newInstance() // default op=ADD, type=INT
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
        val cmp = StockNodeTypes.COMPARE.newInstance()
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
        // MATH(ADD, INT) with only `a` connected → `b` defaults to 0, so result == a.
        val a = StockNodeTypes.CONSTANT.newInstance().also {
            it.config.putString("type", "INT"); it.config.putInt("int", 42)
        }
        val add = StockNodeTypes.MATH.newInstance() // default op=ADD, type=INT
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
        val not = StockNodeTypes.LOGIC_GATE.newInstance().also {
            it.config.putString("op", "NOT")
            // Rebuild inputs to single "in" pin for NOT
            // (EditorState.changeLogicGateOp does this in a live editor;
            //  here we manually replicate the structural change since we
            //  bypass EditorState.)
        }
        // Note: the test wires channelIn.out → not.in, but LOGIC_GATE defaults
        // to 2 binary inputs (a, b). We patch the node's inputs list via a
        // copy to match NOT's single-pin shape so the evaluator key "in" resolves.
        val notNode = not.copy(
            inputs = listOf(dev.nitka.nodewire.graph.Pin("in", "In", dev.nitka.nodewire.graph.PinType.BOOL))
        )
        val g = NodeGraph().apply {
            add(channelIn); add(notNode)
            addEdge(Edge(PinRef(channelIn.id, "out"), PinRef(notNode.id, "in")))
        }
        val r = GraphEvaluator.eval(
            g,
            externalOutputs = mapOf((channelIn.id to "out") to PinValue.Bool(true)),
        )
        assertEquals(PinValue.Bool(false), r.valueAt(notNode.id, "out"))
    }
}
