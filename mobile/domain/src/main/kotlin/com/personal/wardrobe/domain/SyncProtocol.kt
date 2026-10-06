package com.personal.wardrobe.domain

object SyncProtocol {
    fun validateAcknowledgements(pending:List<String>,accepted:List<String>){
        require(accepted.size==accepted.distinct().size&&accepted.toSet()==pending.toSet()){ "Incomplete or unexpected refresh acknowledgement" }
    }
    fun validateRules(rules:Rules,bundled:Rules){
        require(rules.schemaVersion==1&&rules.categories.toSet()==bundled.categories.toSet()&&rules.beamWidth in 1..128&&rules.slotLimit in 1..50&&rules.templates.isNotEmpty()&&rules.templates.all{it.isNotEmpty()&&it.distinct().size==it.size&&rules.categories.containsAll(it)}&&listOf("timeless","balanced","trend").all{mode->rules.weights[mode]?.let{weights->weights.size==3&&weights.all{it.isFinite()&&it in 0.0..1.0}&&kotlin.math.abs(weights.sum()-1.0)<.0001}==true}){ "Unsupported styling database" }
    }
}
