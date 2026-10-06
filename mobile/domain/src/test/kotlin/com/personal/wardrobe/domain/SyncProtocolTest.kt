package com.personal.wardrobe.domain

import com.google.gson.Gson
import org.junit.Test
import java.io.File

class SyncProtocolTest {
    private val rules=Gson().fromJson(File("shared/rules.json").readText(),Rules::class.java)
    @Test fun completeRetryAcknowledgementsAccepted(){val ids=(1..300).map{"change-$it"};SyncProtocol.validateAcknowledgements(ids,ids.reversed());SyncProtocol.validateAcknowledgements(ids,ids)}
    @Test(expected=IllegalArgumentException::class) fun incompleteAcknowledgementsRejected(){SyncProtocol.validateAcknowledgements(listOf("a","b"),listOf("a"))}
    @Test(expected=IllegalArgumentException::class) fun unknownAcknowledgementsRejected(){SyncProtocol.validateAcknowledgements(listOf("a"),listOf("x"))}
    @Test(expected=IllegalArgumentException::class) fun duplicateAcknowledgementsRejected(){SyncProtocol.validateAcknowledgements(listOf("a"),listOf("a","a"))}
    @Test fun bundledRulesAccepted(){SyncProtocol.validateRules(rules,rules)}
    @Test(expected=IllegalArgumentException::class) fun invalidWeightsRejected(){SyncProtocol.validateRules(rules.copy(weights=mapOf("balanced" to listOf(1.0))),rules)}
    @Test(expected=IllegalArgumentException::class) fun oversizedBeamRejected(){SyncProtocol.validateRules(rules.copy(beamWidth=1000),rules)}
}
