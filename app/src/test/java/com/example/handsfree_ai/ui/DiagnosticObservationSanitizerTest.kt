package com.example.handsfree_ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.handsfree_ai.accessibility.AccessibilityServiceConnectionState
import com.example.handsfree_ai.accessibility.AccessibilityScreenObservation
import com.example.handsfree_ai.accessibility.LiveServiceObservationSnapshot
import com.example.handsfree_ai.accessibility.ObservedUiElement
import com.example.handsfree_ai.accessibility.ScreenObservationStatus
import com.example.handsfree_ai.accessibility.AccessibilityElementMapper

class DiagnosticObservationSanitizerTest {
    @Test fun callerSuppliedObservationIsRedactedAndHashRecomputed() {
        val raw = DiagnosticScreenObservation(
            packageName = "example.app",
            screenHash = "caller hash that may encode raw data",
            elements = listOf(
                DiagnosticScreenElement(
                    id = "card_input", text = "Jane Doe", description = "Cardholder name",
                    actions = listOf("set cardholder value"), editable = true
                ),
                DiagnosticScreenElement(id = "amount", text = "Continue", clickable = true)
            )
        )

        val safe = sanitizeDiagnosticObservation(raw)!!.value

        assertEquals("", safe.elements[0].text)
        assertEquals("", safe.elements[0].description)
        assertTrue(safe.elements[0].sensitive)
        assertTrue(safe.elements[0].actions.isEmpty())
        assertEquals("", safe.elements[1].text)
        assertTrue(safe.elements[1].actions.isEmpty())
        assertNotEquals(raw.screenHash, safe.screenHash)
    }

    @Test fun genericUnlabeledCallerContentAndActionsFailClosed() {
        val raw = DiagnosticScreenObservation(
            elements = listOf(
                DiagnosticScreenElement(id="e_1", text="654321", description="", actions=listOf("654321", "Tap")),
                DiagnosticScreenElement(id="e_2", text="4111 1111 1111 1111", description="", actions=listOf("Use this")),
                DiagnosticScreenElement(id="e_3", text="Private note", description="Unlabeled detail", bounds=listOf(1,2,3,4), clickable=true)
            )
        )

        val safe = sanitizeDiagnosticObservation(raw)!!.value
        assertTrue(safe.elements.all { it.text.isEmpty() && it.description.isEmpty() && it.actions.isEmpty() })
        assertEquals(listOf(1, 2, 3, 4), safe.elements[2].bounds)
        assertTrue(safe.elements[2].clickable)
        assertNotEquals(raw.screenHash, safe.screenHash)
    }

    @Test fun onlySanitizedLiveSourceCanPreserveOrdinaryText() {
        val live = AccessibilityScreenObservation(
            packageName="example.app", screenHash="untrusted incoming hash", status=ScreenObservationStatus.OBSERVED,
            elements=listOf(
                ObservedUiElement("e_0", "Continue", "", emptyList(), true, false, false),
                ObservedUiElement("e_1", "654321", "", emptyList(), false, false, false),
                ObservedUiElement("e_2", "4111-1111-1111-1111", "", emptyList(), false, false, false)
            )
        )

        AccessibilityServiceConnectionState.publish(live)
        try {
            val snapshot = LiveServiceObservationSnapshot.current()!!
            val safe = SanitizedDiagnosticObservation.fromLiveService(snapshot).value
            assertEquals("Continue", safe.elements[0].text)
            assertEquals("", safe.elements[1].text)
            assertEquals("", safe.elements[2].text)
            assertNotEquals(live.screenHash, safe.screenHash)
        } finally {
            AccessibilityServiceConnectionState.clearObservation()
        }
    }

    @Test fun callerSuppliedPasswordMetadataAndContextAreRedacted() {
        val raw = DiagnosticScreenObservation(
            elements = listOf(
                DiagnosticScreenElement(id="password_label", text="Password", parentId="form"),
                DiagnosticScreenElement(id="secret", text="hunter2", description="current value", editable=true, parentId="form"),
                DiagnosticScreenElement(id="cvv_field", text="987", description="", editable=false),
                DiagnosticScreenElement(id="ordinary", text="Ordinary label")
            )
        )

        val safe = sanitizeDiagnosticObservation(raw)!!.value
        assertEquals("", safe.elements[0].text)
        assertEquals("", safe.elements[1].text)
        assertEquals("", safe.elements[1].description)
        assertTrue(safe.elements[1].sensitive)
        assertEquals("", safe.elements[2].text)
        assertTrue(safe.elements[2].sensitive)
        assertEquals("", safe.elements[3].text)
        assertFalse(safe.elements[3].sensitive)
    }

    @Test fun callerSuppliedDataIsCappedAndDuplicateIdsAreMadeUnique() {
        val raw = DiagnosticScreenObservation(
            elements = (0..40).map { index ->
                DiagnosticScreenElement(id="duplicate", text="Item $index")
            }
        )

        val safe = sanitizeDiagnosticObservation(raw)!!.value
        assertEquals(40, safe.elements.size)
        assertTrue(safe.truncated)
        assertEquals(safe.elements.size, safe.elements.map { it.id }.toSet().size)
    }

    @Test fun sanitizedObservationAndNestedListsCannotBeMutated() {
        val sourceBounds = mutableListOf(1, 2, 30, 40)
        val sourceActions = mutableListOf("untrusted action")
        val safe = sanitizeDiagnosticObservation(
            DiagnosticScreenObservation(
                packageName="example.app",
                elements=listOf(DiagnosticScreenElement(
                    id="e_0", bounds=sourceBounds, actions=sourceActions,
                    clickable=true, editable=false, sensitive=false
                ))
            )
        )!!.value
        val screenHashBefore = safe.screenHash
        val storedElement = safe.elements.single()
        val expectedHash = AccessibilityElementMapper.screenHash(
            safe.packageName,
            safe.elements.map { item ->
                ObservedUiElement(item.id, item.text, item.description, item.bounds, item.clickable,
                    item.editable, item.sensitive, item.parentId, item.actions)
            },
            safe.truncated
        )

        assertMutationRejected { (safe.elements as MutableList<DiagnosticScreenElement>).add(storedElement) }
        assertMutationRejected { (storedElement.bounds as MutableList<Int>)[0] = 99 }
        assertMutationRejected { (storedElement.actions as MutableList<String>).add("injected") }
        sourceBounds[0] = 99
        sourceActions.add("changed source")

        assertEquals(listOf(1, 2, 30, 40), safe.elements.single().bounds)
        assertTrue(safe.elements.single().actions.isEmpty())
        assertEquals(screenHashBefore, safe.screenHash)
        assertEquals(expectedHash, safe.screenHash)
    }

    @Test fun diagnosticRecordListsAreDefensiveAndUnmodifiable() {
        val source = mutableListOf(DiagnosticActionRecord("proposed", "pending"))
        val snapshot = immutableDiagnosticList(source)
        source.clear()
        assertEquals(1, snapshot.size)
        assertMutationRejected { (snapshot as MutableList<DiagnosticActionRecord>).clear() }
        assertEquals("proposed", snapshot.single().action)
    }

    private fun assertMutationRejected(mutation: () -> Unit) {
        var rejected = false
        try {
            mutation()
        } catch (_: UnsupportedOperationException) {
            rejected = true
        } catch (_: ClassCastException) {
            rejected = true
        }
        assertTrue("Mutation should be rejected", rejected)
    }
}
