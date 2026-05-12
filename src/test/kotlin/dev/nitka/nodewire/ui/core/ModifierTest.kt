package dev.nitka.nodewire.ui.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private data class TagModifier(val tag: String) : Modifier.Element<TagModifier>

class ModifierTest {
    @Test fun emptyModifierFoldsToInitial() {
        val seen = Modifier.foldIn(mutableListOf<String>()) { acc, el ->
            (el as? TagModifier)?.tag?.let(acc::add); acc
        }
        assertTrue(seen.isEmpty())
    }

    @Test fun thenChainsLeftToRight() {
        val chain = Modifier
            .then(TagModifier("a"))
            .then(TagModifier("b"))
            .then(TagModifier("c"))
        val seen = chain.foldIn(mutableListOf<String>()) { acc, el ->
            (el as? TagModifier)?.tag?.let(acc::add); acc
        }
        assertEquals(listOf("a", "b", "c"), seen)
    }

    @Test fun foldOutReversesOrder() {
        val chain = Modifier
            .then(TagModifier("a"))
            .then(TagModifier("b"))
            .then(TagModifier("c"))
        val seen = chain.foldOut(mutableListOf<String>()) { el, acc ->
            (el as? TagModifier)?.tag?.let(acc::add); acc
        }
        assertEquals(listOf("c", "b", "a"), seen)
    }

    @Test fun thenWithEmptyReturnsSelf() {
        val a = TagModifier("a")
        val combined = a.then(Modifier)
        // CombinedModifier's special-case returns the original Element when other === Modifier
        assertSame(a, combined)
    }

    @Test fun anyAndAll() {
        val chain = Modifier.then(TagModifier("a")).then(TagModifier("b"))
        assertTrue(chain.any { (it as TagModifier).tag == "a" })
        assertTrue(chain.all { it is TagModifier })
    }
}
