package com.example.handsfree_ai.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityElementMapperTest {
    @Test fun passwordInputTypesAreSensitive() {
        assertTrue(AccessibilityElementMapper.hasPasswordInputType(0x00000001 or 0x00000080))
        assertTrue(AccessibilityElementMapper.hasPasswordInputType(0x00000002 or 0x00000010))
        assertFalse(AccessibilityElementMapper.hasPasswordInputType(0x00000001 or 0x00000001))
    }

    @Test fun sensitiveTextAndDescriptionAreRemoved() {
        listOf("password", "OTP", "PIN", "CVV", "payment card").forEach { label ->
            val mapped = AccessibilityElementMapper.map(
                "example.app",
                listOf(AccessibilityNodeCandidate("0", text="123456", description=label, editable=true))
            )
            assertTrue("$label should be detected", mapped.elements.single().sensitive)
            assertEquals("", mapped.elements.single().text)
            assertEquals("", mapped.elements.single().description)
        }
    }

    @Test fun expandedPaymentLabelsAndPunctuationAreDetected() {
        listOf(
            "Cardholder name", "Card-holder", "Expiration date", "Expiry date", "Exp. date",
            "Billing address", "Billing-address", "Billing postcode", "Name on card", "Payment method",
            "C.V.V.", "CVV2", "O-T-P", "PIN", "Enter PIN", "Bank account", "Routing number"
        ).forEach { label ->
            assertTrue("$label should be detected", AccessibilityElementMapper.containsSensitiveKeyword(label))
        }
        listOf("Card view", "Saved cards", "Expiration reminder", "Shipping address", "Pin to home").forEach { label ->
            assertFalse("$label should remain ordinary", AccessibilityElementMapper.containsSensitiveKeyword(label))
        }
    }

    @Test fun standaloneOtpAndFormattedCardValuesAreDetected() {
        listOf("123456", "12 34 56", "123-45678", "4111111111111111", "4111 1111 1111 1111", "4111-1111-1111-1111")
            .forEach { value -> assertTrue("$value should be redacted", AccessibilityElementMapper.isLikelySensitiveValue(value)) }
        listOf("12345", "\$12.34", "order 123456").forEach { value ->
            assertFalse("$value should not match a standalone credential", AccessibilityElementMapper.isLikelySensitiveValue(value))
        }
    }

    @Test fun childCountAndDepthCutoffsAreReportedAsOmissions() {
        assertTrue(omitsChildrenAtTraversalBounds(childCount=101, depth=2, maxChildren=100, maxDepth=40))
        assertTrue(omitsChildrenAtTraversalBounds(childCount=1, depth=40, maxChildren=100, maxDepth=40))
        assertFalse(omitsChildrenAtTraversalBounds(childCount=100, depth=39, maxChildren=100, maxDepth=40))
    }

    @Test fun idsAreStableForSameTreePaths() {
        val input = listOf(
            AccessibilityNodeCandidate("0/1", text="Continue", clickable=true),
            AccessibilityNodeCandidate("0/2", text="Email", editable=true)
        )
        val first = AccessibilityElementMapper.map("example.app", input)
        val second = AccessibilityElementMapper.map("example.app", input)
        assertEquals(first.elements.map { it.id }, second.elements.map { it.id })
        assertEquals(listOf("e_0_1", "e_0_2"), first.elements.map { it.id })
    }

    @Test fun rootAndChildPathsProduceDistinctIds() {
        val result = AccessibilityElementMapper.map(
            "example.app",
            listOf(AccessibilityNodeCandidate("", text="Root"), AccessibilityNodeCandidate("0", text="Child"))
        )
        assertEquals(listOf("e_root", "e_0"), result.elements.map { it.id })
    }

    @Test fun duplicatePathsReceiveDeterministicUniqueIds() {
        val input = listOf(
            AccessibilityNodeCandidate("0/1", text="First"),
            AccessibilityNodeCandidate("0/1", text="Second")
        )
        val one = AccessibilityElementMapper.map("example.app", input)
        val two = AccessibilityElementMapper.map("example.app", input)
        assertEquals(listOf("e_0_1", "e_0_1_2"), one.elements.map { it.id })
        assertEquals(one.elements.map { it.id }, two.elements.map { it.id })
    }

    @Test fun hashIsDeterministicAndReflectsObservedStructure() {
        val first = AccessibilityElementMapper.map("example.app", listOf(AccessibilityNodeCandidate("0", text="Home")))
        val repeat = AccessibilityElementMapper.map("example.app", listOf(AccessibilityNodeCandidate("0", text="Home")))
        val changed = AccessibilityElementMapper.map("example.app", listOf(AccessibilityNodeCandidate("0", text="Settings")))
        val moved = AccessibilityElementMapper.map("example.app", listOf(AccessibilityNodeCandidate("0", text="Home", bounds=listOf(1, 2, 3, 4))))
        assertEquals(first.screenHash, repeat.screenHash)
        assertNotEquals(first.screenHash, changed.screenHash)
        assertNotEquals(first.screenHash, moved.screenHash)
    }

    @Test fun mappingCapsUsefulElementsAtForty() {
        val input = (0..60).map { AccessibilityNodeCandidate("$it", text="Item $it") }
        val result = AccessibilityElementMapper.map("example.app", input)
        assertEquals(AccessibilityElementMapper.MAX_ELEMENTS, result.elements.size)
        assertTrue(result.truncated)
    }

    @Test fun traversalTruncationIsExplicitAndAffectsHash() {
        val candidates = listOf(AccessibilityNodeCandidate("0", text="Home"))
        val complete = AccessibilityElementMapper.map("example.app", candidates)
        val partial = AccessibilityElementMapper.map("example.app", candidates, sourceTruncated = true)
        assertFalse(complete.truncated)
        assertTrue(partial.truncated)
        assertNotEquals(complete.screenHash, partial.screenHash)
    }

    @Test fun emptyAndUnhelpfulNodesProduceEmptyObservation() {
        val result = AccessibilityElementMapper.map("example.app", listOf(AccessibilityNodeCandidate("0")))
        assertTrue(result.elements.isEmpty())
        assertEquals(ScreenObservationStatus.EMPTY, result.status)
    }
}
