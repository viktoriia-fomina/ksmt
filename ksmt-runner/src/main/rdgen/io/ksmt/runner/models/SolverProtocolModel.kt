package io.ksmt.runner.models

import com.jetbrains.rd.generator.nova.Root
import com.jetbrains.rd.generator.nova.Ext
import com.jetbrains.rd.generator.nova.PredefinedType
import com.jetbrains.rd.generator.nova.async
import com.jetbrains.rd.generator.nova.call
import com.jetbrains.rd.generator.nova.field
import com.jetbrains.rd.generator.nova.immutableList
import com.jetbrains.rd.generator.nova.map
import com.jetbrains.rd.generator.nova.nullable

object SolverProtocolRoot : Root()

@Suppress("unused")
object SolverProtocolModel : Ext(SolverProtocolRoot) {
    private val kastType = kastType()
    private val statusType = solverStatusType()

    private val createSolverParams = structdef {
        field("type", enum("SolverType") {
            +"Z3"
            +"Bitwuzla"
            +"Yices"
            +"Cvc5"
            +"Custom"
        })
        field("contextSimplificationMode", enum("ContextSimplificationMode") {
            +"SIMPLIFY"
            +"NO_SIMPLIFY"
        })
        field("customSolverQualifiedName", PredefinedType.string.nullable)
        field("customSolverConfigBuilderQualifiedName", PredefinedType.string.nullable)
    }

    private val solverConfigurationParam = structdef {
        field("kind", enum("ConfigurationParamKind") {
            +"String"
            +"Bool"
            +"Int"
            +"Double"
        })
        field("name", PredefinedType.string)
        field("value", PredefinedType.string)
    }

    private val softConstraint = structdef {
        field("expression", kastType)
        field("weight", PredefinedType.uint)
    }

    private val assertParams = structdef {
        field("expression", kastType)
    }

    private val bulkAssertParams = structdef {
        field("expressions", immutableList(kastType))
    }

    private val popParams = structdef {
        field("levels", PredefinedType.uint)
    }

    private val checkParams = structdef {
        field("timeout", PredefinedType.long)
    }

    private val checkResult = structdef {
        field("status", statusType)
    }

    private val checkMaxSMTParams = structdef {
        field("timeout", PredefinedType.long)
        field("collectStatistics", PredefinedType.bool)
    }

    private val checkMaxSMTResult = structdef {
        field("satSoftConstraintExprs", immutableList(kastType))
        field("satSoftConstraintWeights", immutableList(PredefinedType.uint))
        field("hardConstraintsSatStatus", statusType)
        field("timeoutExceededOrUnknown", PredefinedType.bool)
        field("maxSMTSucceeded", PredefinedType.bool)
    }

    private val checkWithAssumptionsParams = structdef {
        field("assumptions", immutableList(kastType))
        field("timeout", PredefinedType.long)
    }

    private val collectMaxSMTStatisticsResult = structdef {
        field("timeoutMs", PredefinedType.long)
        field("elapsedTimeMs", PredefinedType.long)
        field("timeInSolverQueriesMs", PredefinedType.long)
        field("queriesToSolverNumber", PredefinedType.int)
    }

    private val unsatCoreResult = structdef {
        field("core", immutableList(kastType))
    }

    private val reasonUnknownResult = structdef {
        field("reasonUnknown", PredefinedType.string)
    }

    private val modelFuncInterpEntry = structdef {
        field("hasVars", PredefinedType.bool)
        field("args", immutableList(kastType))
        field("value", kastType)
    }

    private val modelEntry = structdef {
        field("decl", kastType)
        field("vars", immutableList(kastType).nullable)
        field("entries", immutableList(modelFuncInterpEntry))
        field("default", kastType.nullable)
    }

    private val modelUninterpretedSortUniverse = structdef {
        field("sort", kastType)
        field("universe", immutableList(kastType))
    }

    private val modelResult = structdef {
        field("declarations", immutableList(kastType))
        field("interpretations", immutableList(modelEntry))
        field("uninterpretedSortUniverse", immutableList(modelUninterpretedSortUniverse))
    }

    init {
        call("initSolver", createSolverParams, PredefinedType.void).apply {
            async
            documentation = "Initialize solver"
        }
        call("deleteSolver", PredefinedType.void, PredefinedType.void).apply {
            async
            documentation = "Delete solver"
        }
        call("configure", immutableList(solverConfigurationParam), PredefinedType.void).apply {
            async
            documentation = "Configure solver with parameters"
        }
        call("assert", assertParams, PredefinedType.void).apply {
            async
            documentation = "Assert expression"
        }
        call("assertSoft", softConstraint, PredefinedType.void).apply {
            async
            documentation = "Assert expression softly"
        }
        call("bulkAssert", bulkAssertParams, PredefinedType.void).apply {
            async
            documentation = "Assert multiple expressions"
        }
        call("assertAndTrack", assertParams, PredefinedType.void).apply {
            async
            documentation = "Assert and track expression"
        }
        call("bulkAssertAndTrack", bulkAssertParams, PredefinedType.void).apply {
            async
            documentation = "Assert and track multiple expressions"
        }
        call("push", PredefinedType.void, PredefinedType.void).apply {
            async
            documentation = "Solver push"
        }
        call("pop", popParams, PredefinedType.void).apply {
            async
            documentation = "Solver pop"
        }
        call("check", checkParams, checkResult).apply {
            async
            documentation = "Check SAT"
        }
        call("checkMaxSMT", checkMaxSMTParams, checkMaxSMTResult).apply {
            async
            documentation = "Check MaxSMT"
        }
        call("checkSubOptMaxSMT", checkMaxSMTParams, checkMaxSMTResult).apply {
            async
            documentation = "Check SubOptMaxSMT"
        }
        call("collectMaxSMTStatistics", PredefinedType.void, collectMaxSMTStatisticsResult).apply {
            async
            documentation = "Collect MaxSMT statistics"
        }
        call("checkWithAssumptions", checkWithAssumptionsParams, checkResult).apply {
            async
            documentation = "Check SAT with assumptions"
        }
        call("model", PredefinedType.void, modelResult).apply {
            async
            documentation = "Get model"
        }
        call("unsatCore", PredefinedType.void, unsatCoreResult).apply {
            async
            documentation = "Get unsat core"
        }
        call("reasonOfUnknown", PredefinedType.void, reasonUnknownResult).apply {
            async
            documentation = "Get reason of unknown"
        }
        call("interrupt", PredefinedType.void, PredefinedType.void).apply {
            async
            documentation = "Interrupt current check SAT"
        }
    }
}