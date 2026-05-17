package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.GroupTemplate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class GroupTemplateStoreTest {

    @Test fun flowForSameFileIsShared() {
        val store = GroupTemplateStore()
        val a = store.flowOf("x")
        val b = store.flowOf("x")
        assertSame(a, b)
    }

    @Test fun flowsForDifferentFilesAreDistinct() {
        val store = GroupTemplateStore()
        assertNotSame(store.flowOf("x"), store.flowOf("y"))
    }

    @Test fun publishUpdatesFlow() = runBlocking {
        val store = GroupTemplateStore()
        val tpl = GroupTemplate(emptyMap(), emptyList(), emptyList())
        store.publish("x", tpl)
        assertEquals(tpl, store.flowOf("x").filterNotNull().first())
    }
}
