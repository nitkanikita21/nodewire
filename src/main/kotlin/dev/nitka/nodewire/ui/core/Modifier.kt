/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * --
 * Adapted for Nodewire from the Compose UI Modifier interface. Same
 * fold/then/Element semantics; no androidx dependency.
 */
package dev.nitka.nodewire.ui.core

interface Modifier {
    fun <R> foldIn(initial: R, operation: (R, Element<*>) -> R): R
    fun <R> foldOut(initial: R, operation: (Element<*>, R) -> R): R
    fun any(predicate: (Element<*>) -> Boolean): Boolean
    fun all(predicate: (Element<*>) -> Boolean): Boolean

    infix fun then(other: Modifier): Modifier =
        if (other === Modifier) this else CombinedModifier(this, other)

    interface Element<Self : Element<Self>> : Modifier {
        override fun <R> foldIn(initial: R, operation: (R, Element<*>) -> R): R =
            operation(initial, this)

        override fun <R> foldOut(initial: R, operation: (Element<*>, R) -> R): R =
            operation(this, initial)

        override fun any(predicate: (Element<*>) -> Boolean): Boolean = predicate(this)
        override fun all(predicate: (Element<*>) -> Boolean): Boolean = predicate(this)

        /** Merge two elements of the same concrete type. Default: prefer `other` (last-wins). */
        fun mergeWith(other: Self): Self = other

        @Suppress("UNCHECKED_CAST")
        fun unsafeMergeWith(other: Element<*>): Element<*> = mergeWith(other as Self)
    }

    companion object : Modifier {
        override fun <R> foldIn(initial: R, operation: (R, Element<*>) -> R): R = initial
        override fun <R> foldOut(initial: R, operation: (Element<*>, R) -> R): R = initial
        override fun any(predicate: (Element<*>) -> Boolean): Boolean = false
        override fun all(predicate: (Element<*>) -> Boolean): Boolean = true
        override infix fun then(other: Modifier): Modifier = other
        override fun toString() = "Modifier"
    }
}

class CombinedModifier(
    private val outer: Modifier,
    private val inner: Modifier,
) : Modifier {
    override fun <R> foldIn(initial: R, operation: (R, Modifier.Element<*>) -> R): R =
        inner.foldIn(outer.foldIn(initial, operation), operation)

    override fun <R> foldOut(initial: R, operation: (Modifier.Element<*>, R) -> R): R =
        outer.foldOut(inner.foldOut(initial, operation), operation)

    override fun any(predicate: (Modifier.Element<*>) -> Boolean): Boolean =
        outer.any(predicate) || inner.any(predicate)

    override fun all(predicate: (Modifier.Element<*>) -> Boolean): Boolean =
        outer.all(predicate) && inner.all(predicate)

    override fun equals(other: Any?): Boolean =
        other is CombinedModifier && outer == other.outer && inner == other.inner

    override fun hashCode(): Int = outer.hashCode() + 31 * inner.hashCode()

    override fun toString() = "[" + foldIn("") { acc, element ->
        if (acc.isEmpty()) element.toString() else "$acc, $element"
    } + "]"
}

/**
 * Modifier elements that affect layout (size, padding, margin, flex, position).
 * Implementations project themselves onto a [org.appliedenergistics.yoga.YogaNode],
 * which is the only layout backend we use. UiNode collects these via [Modifier.foldIn]
 * and calls [applyTo] in order so later elements in the chain win.
 */
interface LayoutModifierElement<Self : LayoutModifierElement<Self>> : Modifier.Element<Self> {
    fun applyTo(yoga: org.appliedenergistics.yoga.YogaNode)
}

/**
 * Marker for modifier elements that affect rendering only (background,
 * border, shadow). Read by Renderer / SurfaceRenderer.
 */
interface StyleModifierElement<Self : StyleModifierElement<Self>> : Modifier.Element<Self>

/**
 * Marker for modifier elements that handle pointer/key events
 * (clickable, pointerInput, onHover). Read by HitTester.
 */
interface InputModifierElement<Self : InputModifierElement<Self>> : Modifier.Element<Self>
