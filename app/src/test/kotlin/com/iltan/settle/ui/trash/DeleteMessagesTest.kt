package com.iltan.settle.ui.trash

import com.iltan.settle.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteMessagesTest {

    @Test
    fun androidsConfirmationIsExplainedOnceAndOnlyWhileItAsks() {
        assertTrue(explainBeforeDeleting(canDeleteWithoutAsking = false, explainerShown = false))
        assertFalse(explainBeforeDeleting(canDeleteWithoutAsking = false, explainerShown = true))
        assertFalse(explainBeforeDeleting(canDeleteWithoutAsking = true, explainerShown = false))
    }

    @Test
    fun backedUpItemsJustMoveToTheTrash() {
        assertEquals(DeletedMessage(R.plurals.trash_moved, 1), deletedMessage(1, 0))
        assertEquals(DeletedMessage(R.plurals.trash_moved, 4), deletedMessage(4, 0))
    }

    @Test
    fun itSaysPlainlyHowManyWerentBackedUp() {
        assertEquals(DeletedMessage(R.plurals.trash_moved_not_backed_up, 3), deletedMessage(8, 3))
    }

    @Test
    fun whenNoneWereBackedUpItSaysSoWithoutCounting() {
        assertEquals(
            DeletedMessage(R.plurals.trash_moved_none_backed_up, 1),
            deletedMessage(1, 1),
        )
        assertEquals(
            DeletedMessage(R.plurals.trash_moved_none_backed_up, 5),
            deletedMessage(5, 5),
        )
    }
}
