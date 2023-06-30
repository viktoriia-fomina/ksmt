package io.ksmt.solver.maxsat

import io.ksmt.KContext
import io.ksmt.expr.KEqExpr
import io.ksmt.expr.KExpr
import io.ksmt.expr.KNotExpr
import io.ksmt.expr.KOrBinaryExpr
import io.ksmt.expr.KOrNaryExpr
import io.ksmt.expr.KTrue
import io.ksmt.solver.KModel
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolverStatus
import io.ksmt.sort.KBoolSort
import io.ksmt.utils.mkConst
import kotlin.time.Duration

class KMaxSATSolver<T>(private val ctx: KContext, private val solver: KSolver<T>) : KSolver<KSolverConfiguration>
        where T : KSolverConfiguration {
    private val scopeManager = MaxSATScopeManager()

    private var softConstraints = mutableListOf<SoftConstraint>()

    /**
     * Assert softly an expression with weight (aka soft constraint) into solver.
     *
     * @see checkMaxSAT
     * */
    fun assertSoft(expr: KExpr<KBoolSort>, weight: Int) {
        require(weight > 0) { "Soft constraint weight must be greater than 0" }

        val softConstraint = SoftConstraint(expr, weight)
        softConstraints.add(softConstraint)
        scopeManager.incrementSoft()
    }

    // TODO: add timeout support
    /**
     * Solve maximum satisfiability problem.
     */
    fun checkMaxSAT(): KMaxSATResult {
        if (softConstraints.isEmpty()) {
            return KMaxSATResult(listOf(), solver.check(), true)
        }

        val status = solver.check()

        if (status == KSolverStatus.UNSAT) {
            return KMaxSATResult(listOf(), status, true)
        } else if (status == KSolverStatus.UNKNOWN) {
            return KMaxSATResult(listOf(), status, false)
        }

        var i = 0
        var formula = softConstraints.toMutableList()

        unionSoftConstraintsWithSameExpressions(formula)

        solver.push()

        while (true) {
            val (solverStatus, unsatCore, model) = checkSAT(formula)


            if (solverStatus == KSolverStatus.SAT) {
                solver.pop()
                val satSoftConstraints =
                    softConstraints.filter { model!!.eval(it.expression).internEquals(KTrue(ctx)) }
                return KMaxSATResult(satSoftConstraints, solverStatus, true)
            } else if (solverStatus == KSolverStatus.UNKNOWN) {
                // TODO: implement
                solver.pop()
            }

            val (weight, splitUnsatCore) = splitUnsatCore(formula, unsatCore)

            val (formulaReified, reificationVariables) =
                    reifyUnsatCore(formula, splitUnsatCore, i, weight)

            when (reificationVariables.size) {
                1 -> assert(reificationVariables.first())
                2 -> assert(KOrBinaryExpr(ctx, reificationVariables[0], reificationVariables[1]))
                else -> assert(KOrNaryExpr(ctx, reificationVariables))
            }

            formula = applyMaxRes(formulaReified, reificationVariables, i)

            i++
        }
    }

    /**
     * Split all soft constraints from the unsat core into two groups:
     * - constraints with the weight equal to the minimum of the unsat core soft constraint weights
     * - constraints with the weight equal to old weight - minimum weight
     *
     * Returns a pair of minimum weight and a list of unsat core soft constraints with minimum weight.
     */
    private fun splitUnsatCore(formula: MutableList<SoftConstraint>, unsatCore: List<KExpr<KBoolSort>>)
            : Pair<Int, List<SoftConstraint>> {
        // Filters soft constraints from the unsat core.
        val unsatCoreSoftConstraints  =
                formula.filter { x -> unsatCore.any { x.expression.internEquals(it) } }

        val minWeight = unsatCoreSoftConstraints.minBy { it.weight }.weight

        val unsatCoreSoftConstraintsSplit = mutableListOf<SoftConstraint>()

        unsatCoreSoftConstraints.forEach { x ->
            if (x.weight > minWeight) {
                val minWeightSoftConstraint = SoftConstraint(x.expression, minWeight)
                formula.add(minWeightSoftConstraint)
                formula.add(SoftConstraint(x.expression, x.weight - minWeight))
                formula.removeIf { it.weight == x.weight && it.expression == x.expression }

                unsatCoreSoftConstraintsSplit.add(minWeightSoftConstraint)
            }
            else {
                unsatCoreSoftConstraintsSplit.add(x)
            }
        }

        return Pair(minWeight, unsatCoreSoftConstraintsSplit)
    }

    /**
     * Union soft constraints with same expressions into a single soft constraint. The new soft constraint weight will be
     * equal to the sum of old soft constraints weights.
     */
    private fun unionSoftConstraintsWithSameExpressions(formula: MutableList<SoftConstraint>) {
        var i = 0

        while (i < formula.size) {
            val currentExpr = formula[i].expression

            val similarConstraints = formula.filter { it.expression.internEquals(currentExpr) }

            // Unions soft constraints with same expressions into a single soft constraint.
            if (similarConstraints.size > 1) {
                val similarConstraintsWeightsSum = similarConstraints.sumOf { it.weight }

                formula.removeAll(similarConstraints)
                formula.add(SoftConstraint(currentExpr, similarConstraintsWeightsSum))
            }

            i++
        }
    }

    /**
     * Check on satisfiability hard constraints with assumed soft constraints.
     *
     * Returns a triple of solver status, unsat core (if exists, empty list otherwise) and model
     * (if exists, null otherwise).
     */
    private fun checkSAT(assumptions: List<SoftConstraint>): Triple<KSolverStatus, List<KExpr<KBoolSort>>, KModel?> =
        when (val status = solver.checkWithAssumptions(assumptions.map { x -> x.expression })) {
            KSolverStatus.SAT -> Triple(status, listOf(), solver.model())
            KSolverStatus.UNSAT -> Triple(status, solver.unsatCore(), null)
            KSolverStatus.UNKNOWN -> Triple(status, listOf(), null)
        }

    /**
     * Reify unsat core soft constraints with literals.
     */
    private fun reifyUnsatCore(formula: MutableList<SoftConstraint>, unsatCore: List<SoftConstraint>,
                               iter: Int, weight: Int): Pair<MutableList<SoftConstraint>, List<KExpr<KBoolSort>>> {
        val literalsToReify = mutableListOf<KExpr<KBoolSort>>()

        for (coreElement in unsatCore.withIndex()) {
            if (coreElement.value.weight == weight) {
                formula.remove(coreElement.value)

                val coreElementExpr = coreElement.value.expression
                val literalToReify = coreElementExpr.sort.mkConst("*$iter${coreElement.index}")

                val constraintToReify = KEqExpr(
                    ctx,
                    coreElementExpr,
                    KNotExpr(ctx, literalToReify),
                )

                assert(constraintToReify)

                literalsToReify.add(literalToReify)
            }
        }

        return Pair(formula, literalsToReify)
    }

    /**
     * Apply MaxRes rule.
     */
    private fun applyMaxRes(formula: MutableList<SoftConstraint>, literalsToReify: List<KExpr<KBoolSort>>,
                            iter: Int)
            : MutableList<SoftConstraint> {
        for (indexedLiteral in literalsToReify.withIndex()) {
            // TODO: here we should use restrictions from the article for MaxRes

            val index = indexedLiteral.index
            val indexLast = literalsToReify.lastIndex

            if (index < indexLast) {
                val disjunction =
                    // We do not take the current literal to reify (from the next to the last)
                    when (indexLast - index) {
                        1 -> literalsToReify[index + 1]
                        2 -> KOrBinaryExpr(ctx, literalsToReify[index + 1], literalsToReify[index + 2])
                        else -> KOrNaryExpr(
                            ctx,
                            literalsToReify.subList(index + 1, indexLast + 1),
                        )
                    }

                val literalToReifyDisjunction = ctx.boolSort.mkConst("#$iter$index")

                assert(
                    KEqExpr(
                        ctx,
                        literalToReifyDisjunction,
                        disjunction,
                    ),
                )
            }
        }

        return formula
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
        scopeManager.push()
    }

    override fun pop(n: UInt) {
        solver.pop(n)
        softConstraints = scopeManager.pop(n, softConstraints)
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
