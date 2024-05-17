package io.ksmt.solver.runner

import com.jetbrains.rd.framework.IProtocol
import com.jetbrains.rd.util.lifetime.Lifetime
import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.runner.core.ChildProcessBase
import io.ksmt.runner.core.KsmtWorkerArgs
import io.ksmt.runner.generated.createInstance
import io.ksmt.runner.generated.createSolverConstructor
import io.ksmt.runner.generated.models.CheckMaxSMTResult
import io.ksmt.runner.generated.models.CheckResult
import io.ksmt.runner.generated.models.CollectMaxSMTStatisticsResult
import io.ksmt.runner.generated.models.ContextSimplificationMode
import io.ksmt.runner.generated.models.ModelEntry
import io.ksmt.runner.generated.models.ModelFuncInterpEntry
import io.ksmt.runner.generated.models.ModelResult
import io.ksmt.runner.generated.models.ModelUninterpretedSortUniverse
import io.ksmt.runner.generated.models.ReasonUnknownResult
import io.ksmt.runner.generated.models.SoftConstraint
import io.ksmt.runner.generated.models.SolverProtocolModel
import io.ksmt.runner.generated.models.SolverType
import io.ksmt.runner.generated.models.UnsatCoreResult
import io.ksmt.runner.generated.models.solverProtocolModel
import io.ksmt.runner.serializer.AstSerializationCtx
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.maxsmt.KMaxSMTContext
import io.ksmt.solver.maxsmt.solvers.KMaxSMTSolverBase
import io.ksmt.solver.maxsmt.solvers.KPrimalDualMaxResSolver
import io.ksmt.solver.model.KFuncInterp
import io.ksmt.solver.model.KFuncInterpEntry
import io.ksmt.solver.model.KFuncInterpEntryWithVars
import io.ksmt.solver.model.KFuncInterpWithVars
import io.ksmt.sort.KBoolSort
import kotlin.time.Duration.Companion.milliseconds

class KSolverWorkerProcess : ChildProcessBase<SolverProtocolModel>() {
    private var workerCtx: KContext? = null
    private var workerSolver: KSolver<*>? = null
    private val customSolverCreators = hashMapOf<String, (KContext) -> KSolver<*>>()

    private val ctx: KContext
        get() = workerCtx ?: error("Solver is not initialized")

    private val solver: KSolver<*>
        get() = workerSolver ?: error("Solver is not initialized")

    private val maxSmtSolver: KMaxSMTSolverBase<KSolverConfiguration>
        get() = KPrimalDualMaxResSolver(ctx, solver, KMaxSMTContext(preferLargeWeightConstraintsForCores = true))

    override fun parseArgs(args: Array<String>) = KsmtWorkerArgs.fromList(args.toList())

    override fun initProtocolModel(protocol: IProtocol): SolverProtocolModel =
        protocol.solverProtocolModel

    @Suppress("LongMethod")
    override fun SolverProtocolModel.setup(astSerializationCtx: AstSerializationCtx, lifetime: Lifetime) {
        initSolver.measureExecutionForTermination { params ->
            check(workerCtx == null) { "Solver is initialized" }

            val simplificationMode = when (params.contextSimplificationMode) {
                ContextSimplificationMode.SIMPLIFY -> KContext.SimplificationMode.SIMPLIFY
                ContextSimplificationMode.NO_SIMPLIFY -> KContext.SimplificationMode.NO_SIMPLIFY
            }
            workerCtx = KContext(simplificationMode = simplificationMode)

            astSerializationCtx.initCtx(ctx)

            workerSolver = if (params.type != SolverType.Custom) {
                params.type.createInstance(ctx)
            } else {
                val solverName = params.customSolverQualifiedName
                    ?: error("Custom solver name was not provided")

                val solverCreator = customSolverCreators.getOrPut(solverName) {
                    createSolverConstructor(solverName)
                }

                solverCreator(ctx)
            }
        }
        deleteSolver.measureExecutionForTermination {
            maxSmtSolver.close()
            ctx.close()
            astSerializationCtx.resetCtx()
            workerSolver = null
            workerCtx = null
        }
        configure.measureExecutionForTermination { config ->
            maxSmtSolver.configure {
                config.forEach { addUniversalParam(it) }
            }
        }
        assert.measureExecutionForTermination { params ->
            @Suppress("UNCHECKED_CAST")
            maxSmtSolver.assert(params.expression as KExpr<KBoolSort>)
        }
        assertSoft.measureExecutionForTermination { params ->
            @Suppress("UNCHECKED_CAST")
            maxSmtSolver.assertSoft(params.expression as KExpr<KBoolSort>, params.weight)
        }
        bulkAssert.measureExecutionForTermination { params ->
            @Suppress("UNCHECKED_CAST")
            maxSmtSolver.assert(params.expressions as List<KExpr<KBoolSort>>)
        }
        assertAndTrack.measureExecutionForTermination { params ->
            @Suppress("UNCHECKED_CAST")
            maxSmtSolver.assertAndTrack(params.expression as KExpr<KBoolSort>)
        }
        bulkAssertAndTrack.measureExecutionForTermination { params ->
            @Suppress("UNCHECKED_CAST")
            maxSmtSolver.assertAndTrack(params.expressions as List<KExpr<KBoolSort>>)
        }
        push.measureExecutionForTermination {
            maxSmtSolver.push()
        }
        pop.measureExecutionForTermination { params ->
            maxSmtSolver.pop(params.levels)
        }
        check.measureExecutionForTermination { params ->
            val timeout = params.timeout.milliseconds
            val status = maxSmtSolver.check(timeout)
            CheckResult(status)
        }
        checkWithAssumptions.measureExecutionForTermination { params ->
            val timeout = params.timeout.milliseconds

            @Suppress("UNCHECKED_CAST")
            val status = maxSmtSolver.checkWithAssumptions(params.assumptions as List<KExpr<KBoolSort>>, timeout)

            CheckResult(status)
        }
        checkMaxSMT.measureExecutionForTermination { params ->
            val timeout = params.timeout.milliseconds

            val result = maxSmtSolver.checkMaxSMT(timeout)

            @Suppress("UNCHECKED_CAST")
            CheckMaxSMTResult(
                result.satSoftConstraints as List<SoftConstraint>,
                result.hardConstraintsSatStatus,
                result.maxSMTSucceeded
            )
        }
        checkSubOptMaxSMT.measureExecutionForTermination { params ->
            val timeout = params.timeout.milliseconds

            val result = maxSmtSolver.checkSubOptMaxSMT(timeout)

            @Suppress("UNCHECKED_CAST")
            CheckMaxSMTResult(
                result.satSoftConstraints as List<SoftConstraint>,
                result.hardConstraintsSatStatus,
                result.maxSMTSucceeded
            )
        }
        collectMaxSMTStatistics.measureExecutionForTermination {
            val statistics = maxSmtSolver.collectMaxSMTStatistics()

            CollectMaxSMTStatisticsResult(
                statistics.timeoutMs,
                statistics.elapsedTimeMs,
                statistics.timeInSolverQueriesMs,
                statistics.queriesToSolverNumber
            )
        }
        model.measureExecutionForTermination {
            val model = maxSmtSolver.model().detach()
            val declarations = model.declarations.toList()
            val interpretations = declarations.map {
                val interp = model.interpretation(it) ?: error("No interpretation for model declaration $it")
                serializeFunctionInterpretation(interp)
            }
            val uninterpretedSortUniverse = model.uninterpretedSorts.map { sort ->
                val universe = model.uninterpretedSortUniverse(sort)
                    ?: error("No universe for uninterpreted sort $it")
                ModelUninterpretedSortUniverse(sort, universe.toList())
            }
            ModelResult(declarations, interpretations, uninterpretedSortUniverse)
        }
        unsatCore.measureExecutionForTermination {
            val core = maxSmtSolver.unsatCore()
            UnsatCoreResult(core)
        }
        reasonOfUnknown.measureExecutionForTermination {
            val reason = maxSmtSolver.reasonOfUnknown()
            ReasonUnknownResult(reason)
        }
        interrupt.measureExecutionForTermination {
            maxSmtSolver.interrupt()
        }
        lifetime.onTermination {
            workerSolver?.close()
            workerCtx?.close()
        }
    }

    private fun serializeFunctionInterpretation(interp: KFuncInterp<*>): ModelEntry {
        val interpEntries = interp.entries.map { serializeFunctionInterpretationEntry(it) }
        val interpVars = if (interp is KFuncInterpWithVars) interp.vars else null
        return ModelEntry(interp.decl, interpVars, interpEntries, interp.default)
    }

    private fun serializeFunctionInterpretationEntry(entry: KFuncInterpEntry<*>) =
        ModelFuncInterpEntry(
            hasVars = entry is KFuncInterpEntryWithVars<*>,
            args = entry.args,
            value = entry.value
        )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            KSolverWorkerProcess().start(args)
        }
    }
}
