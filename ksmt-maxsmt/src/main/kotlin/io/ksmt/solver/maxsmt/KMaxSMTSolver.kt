package io.ksmt.solver.maxsmt

import io.ksmt.KContext
import io.ksmt.expr.KEqExpr
import io.ksmt.expr.KExpr
import io.ksmt.expr.KNotExpr
import io.ksmt.expr.KOrBinaryExpr
import io.ksmt.expr.KOrNaryExpr
import io.ksmt.solver.KModel
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.z3.KZ3Solver
import io.ksmt.sort.KBoolSort
import io.ksmt.utils.mkConst
import kotlin.time.Duration

// TODO: solver type must be KSolver<KSolverConfiguration> but the code does not work with it
class KMaxSMTSolver(private val ctx: KContext, private val solver: KZ3Solver) : KSolver<KSolverConfiguration> {
    private val softConstraints = mutableListOf<SoftConstraint>()

    // Enum checking max SAT state (last check status, was not checked, invalid (soft assertions changed))
    // Should I support push/pop for soft constraints?

    fun assertSoft(expr: KExpr<KBoolSort>, weight: Int) {
        softConstraints.add(SoftConstraint(expr, weight))
    }

    // TODO: return soft constraints
    // TODO: add timeout?
    fun checkMaxSMT(): Pair<KModel?, Int> {
        require(softConstraints.isNotEmpty()) { "Soft constraints list should not be empty" }

        // Should I check every time on satisfiability?
        // У них в солверах есть last checked status.
        // Should I add timeout?
        val status = solver.check()

        if (status == KSolverStatus.UNSAT) {
            error("Conjunction of asserted formulas is UNSAT")
        } else if (status == KSolverStatus.UNKNOWN) {
            // TODO: handle this case
        }

        var i = 0
        var formula = softConstraints.toMutableList()

        while (true) {
            val (solverStatus, unsatCore, model) = solveSMT(formula)

            if (solverStatus == KSolverStatus.SAT) {
                return Pair(model, i)
            } else if (solverStatus == KSolverStatus.UNKNOWN) {
                // TODO: implement
            }

            val (formulaReified, reificationVariables) =
                reifyCore(formula, getUnsatCoreOfConstraints(formula, unsatCore), i)

            // TODO, FIX: Для одного странно использовать KOrNaryExpr
            this.assert(KOrNaryExpr(ctx, reificationVariables))

            formula = applyMaxRes(formulaReified, reificationVariables)

            ++i
        }
    }

    private fun applyMaxRes(formula: MutableList<SoftConstraint>, reificationVariables: List<KExpr<KBoolSort>>): MutableList<SoftConstraint> {
        for (i in reificationVariables.indices) {
            // TODO: here we should use restrictions from the article for MaxRes
            val reificationVar = reificationVariables[i]

            formula.removeIf { x ->
                x.constraint.internEquals(KNotExpr(ctx, reificationVar)) &&
                    x.weight == 1
            }

            // TODO: fix hard/soft constraints sets!
            if (i < reificationVariables.size - 1) {
                // TODO: uncommented, commented in order to build
/*
                val reifiedLiteralsDisjunction = KOrNaryExpr(
                    ctx,
                    reificationVariables.subList(i + 1, reificationVariables.size - 1),
                )
*/

                val reifiedVar = ctx.boolSort.mkConst("d$i")

                // TODO: assert it.
/*                formula.add(
                    HardConstraint(
                        KEqExpr(
                            ctx,
                            reifiedVar,
                            reifiedLiteralsDisjunction,
                        ),
                    ),
                )*/

                formula.add(
                    SoftConstraint(
                        KOrBinaryExpr(
                            ctx,
                            KNotExpr(ctx, reificationVar),
                            KNotExpr(ctx, reifiedVar),
                        ),
                        1,
                    ),
                )
            } else {
                // Здесь добавляем пустой дизъюнкт, но по факту это не нужно делать (т.к. потом его удалим)
            }
        }

        return formula
    }

    private fun getUnsatCoreOfConstraints(formula: MutableList<SoftConstraint>, unsatCore: List<KExpr<KBoolSort>>): List<SoftConstraint> {
        val unsatCoreOfConstraints = mutableListOf<SoftConstraint>()

        for (coreElement in unsatCore) {
            val softConstraint = formula.find { x -> x.constraint == coreElement }
            softConstraint?.let { unsatCoreOfConstraints.add(it) }
        }

        return unsatCoreOfConstraints
    }

    private fun reifyCore(formula: MutableList<SoftConstraint>, unsatCore: List<SoftConstraint>, i: Int): Pair<MutableList<SoftConstraint>, List<KExpr<KBoolSort>>> {
        val unitConstraintExpressions = mutableListOf<KExpr<KBoolSort>>()

        for (coreElement in unsatCore.withIndex()) {
            if (coreElement.value.weight == 1) {
                formula.remove(coreElement.value)

                val coreElementConstraint = coreElement.value.constraint
                // TODO: как реализовать переобозначение? Что если формула встречается как подформула в других формулах?
                val reificationVariable =
                    ctx.boolSort.mkConst("b$i${coreElement.index}")

                val reificationConstraint = KEqExpr(
                    ctx,
                    coreElementConstraint,
                    KNotExpr(ctx, reificationVariable),
                )
                // TODO: Переобозначить и остальные элементы в b_i_j
                this.assert(reificationConstraint)

                formula.add(SoftConstraint(KNotExpr(ctx, reificationVariable), 1))

                unitConstraintExpressions.add(reificationVariable)

                return Pair(formula, unitConstraintExpressions)
            }
        }

        error("reify core method, not implemented part")
    }

    // Returns issat, unsat core (?) and assignment
    private fun solveSMT(assumptions: List<SoftConstraint>): Triple<KSolverStatus, List<KExpr<KBoolSort>>, KModel?> {
        val solverStatus = solver.checkWithAssumptions(assumptions.map { x -> x.constraint })

        if (solverStatus == KSolverStatus.SAT) {
            return Triple(solverStatus, listOf(), solver.model())
        } else if (solverStatus == KSolverStatus.UNSAT) {
            return Triple(solverStatus, solver.unsatCore(), null)
        }

        return Triple(solverStatus, listOf(), null)
    }

    override fun configure(configurator: KSolverConfiguration.() -> Unit) {
        solver.configure(configurator)
    }

    override fun assert(expr: KExpr<KBoolSort>) {
        solver.assert(expr)
    }

    override fun assertAndTrack(expr: KExpr<KBoolSort>) {
        solver.assertAndTrack(expr)
    }

    override fun push() {
        solver.push()
    }

    override fun pop(n: UInt) {
        solver.pop(n)
    }

    override fun check(timeout: Duration): KSolverStatus {
        return solver.check(timeout)
    }

    override fun checkWithAssumptions(assumptions: List<KExpr<KBoolSort>>, timeout: Duration): KSolverStatus {
        return solver.checkWithAssumptions(assumptions, timeout)
    }

    override fun model(): KModel {
        return solver.model()
    }

    override fun unsatCore(): List<KExpr<KBoolSort>> {
        return solver.unsatCore()
    }

    override fun reasonOfUnknown(): String {
        return solver.reasonOfUnknown()
    }

    override fun interrupt() {
        solver.interrupt()
    }

    override fun close() {
        solver.close()
    }
}